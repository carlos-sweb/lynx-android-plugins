package dev.lynx.android.plugins.maps

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import org.json.JSONObject
import java.lang.ref.WeakReference

/** Explicit, foreground-only location; no Google Play Services dependency. */
internal class MapLocation(private val context: Context, private val activity: () -> Activity?, private val updated: (JSONObject) -> Unit) {
    companion object {
        private const val REQUEST = 49873
        private var pending: WeakReference<MapLocation>? = null
        fun onRequestPermissionsResult(code: Int): Boolean {
            if (code != REQUEST) return false
            val location = pending?.get(); pending = null
            location?.finishPermission()
            return true
        }
    }
    private val manager = context.getSystemService(LocationManager::class.java)
    private val main = Handler(Looper.getMainLooper())
    private var listener: LocationListener? = null
    private var completion: ((Result<JSONObject>) -> Unit)? = null
    private var options = JSONObject()
    private val sensors = context.getSystemService(SensorManager::class.java)
    private var lastPosition: JSONObject? = null
    private var lastHeadingEvent = 0L
    private val heading = object : SensorEventListener {
        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
        override fun onSensorChanged(event: SensorEvent) {
            if (tracking != "heading" || listener == null || System.currentTimeMillis() - lastHeadingEvent < 100) return
            val rotation = FloatArray(9); val orientation = FloatArray(3)
            SensorManager.getRotationMatrixFromVector(rotation, event.values)
            SensorManager.getOrientation(rotation, orientation)
            lastHeadingEvent = System.currentTimeMillis()
            lastPosition?.let { position -> updated(JSONObject(position.toString()).put("bearing", (Math.toDegrees(orientation[0].toDouble()) + 360) % 360)) }
        }
    }
    var tracking = "none"
        set(value) {
            if (value == "heading") require(sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR) != null) { "NOT_SUPPORTED: This device has no heading sensor." }
            field = value; updateHeadingSensor()
        }
    private fun updateHeadingSensor() {
        sensors?.unregisterListener(heading)
        if (tracking == "heading" && listener != null) sensors?.getDefaultSensor(Sensor.TYPE_ROTATION_VECTOR)?.let { sensors.registerListener(heading, it, SensorManager.SENSOR_DELAY_UI) }
    }
    private val permissionTimeout = Runnable { pending = null; completion?.invoke(Result.failure(IllegalStateException("TIMEOUT: Location permission timed out."))); completion = null }
    private fun granted(permission: String) = context.checkSelfPermission(permission) == PackageManager.PERMISSION_GRANTED
    fun status(): JSONObject = JSONObject().put("running", listener != null).put("permission", if (granted(Manifest.permission.ACCESS_FINE_LOCATION)) "precise" else if (granted(Manifest.permission.ACCESS_COARSE_LOCATION)) "approximate" else "none").put("providerEnabled", manager?.let { it.isProviderEnabled(LocationManager.GPS_PROVIDER) || it.isProviderEnabled(LocationManager.NETWORK_PROVIDER) } ?: false).put("tracking", tracking)
    fun start(options: JSONObject, done: (Result<JSONObject>) -> Unit) {
        require(options.optLong("interval", 1000) >= 250 && options.optDouble("minDistance", 0.0) >= 0) { "Invalid location interval or distance." }
        this.options = options
        if (status().getString("permission") != "none") { done(runCatching { begin(); status() }); return }
        if (pending?.get() != null) { done(Result.failure(IllegalArgumentException("Another map permission request is active."))); return }
        val host = activity() ?: run { done(Result.failure(IllegalStateException("NOT_SUPPORTED: An Activity host is required."))); return }
        completion = done; pending = WeakReference(this)
        main.postDelayed(permissionTimeout, 60_000)
        host.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), REQUEST)
    }
    private fun finishPermission() {
        main.removeCallbacks(permissionTimeout)
        val callback = completion; completion = null
        callback?.invoke(runCatching {
            check(status().getString("permission") != "none") { "PERMISSION_DENIED: Location permission was denied." }
            begin(); status()
        })
    }
    private fun begin() {
        stop()
        val service = manager ?: error("NOT_SUPPORTED: Location service unavailable.")
        val fine = granted(Manifest.permission.ACCESS_FINE_LOCATION)
        val provider = when {
            fine && options.optBoolean("highAccuracy") && service.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            service.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            fine && service.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> error("POSITION_UNAVAILABLE: Enable location services.")
        }
        val callback = object : LocationListener {
            override fun onLocationChanged(location: Location) { lastPosition = JSONObject().put("latitude", location.latitude).put("longitude", location.longitude).put("accuracy", location.accuracy.toDouble()).put("timestamp", location.time).put("bearing", location.bearing.toDouble()).put("speed", location.speed.toDouble()); updated(lastPosition!!) }
            override fun onProviderDisabled(provider: String) { stop() }
            @Deprecated("Deprecated in Android") override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        service.requestLocationUpdates(provider, options.optLong("interval", 1000), options.optDouble("minDistance", 0.0).toFloat(), callback, Looper.getMainLooper())
        listener = callback
        updateHeadingSensor()
        service.getLastKnownLocation(provider)?.let(callback::onLocationChanged)
    }
    fun stop(): JSONObject {
        listener?.let { try { manager?.removeUpdates(it) } catch (_: SecurityException) { } }; listener = null
        sensors?.unregisterListener(heading)
        return status()
    }
    fun close() {
        stop(); main.removeCallbacks(permissionTimeout)
        if (pending?.get() === this) pending = null
        completion?.invoke(Result.failure(IllegalStateException("MAP_UNMOUNTED: Map was removed."))); completion = null
    }
}
