package dev.lynx.android.plugins.geolocation

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import androidx.core.content.ContextCompat
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule

/** Registers the geolocation bridge and forwards its runtime-permission result. */
object LynxGeolocationPlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxGeolocationPlugin", GeolocationPlugin::class.java)
    }

    /** Returns true when the geolocation bridge consumed the permission result. */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean = GeolocationPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults)
}

/** Foreground, one-shot GPS/network location with Android runtime permission handling. */
class GeolocationPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:geolocation") {
    companion object {
        private const val LOCATION_REQUEST = 49872
        private var pending: GeolocationPlugin? = null
        private var pendingRequestId: String? = null
        private var pendingHighAccuracy = false

        internal fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grants: IntArray): Boolean {
            if (requestCode != LOCATION_REQUEST) return false
            val plugin = pending ?: return true
            val requestId = pendingRequestId ?: return true
            pending = null
            pendingRequestId = null
            val granted = grants.any { it == PackageManager.PERMISSION_GRANTED }
            val fineGranted = permissions.indices.any { index ->
                permissions[index] == Manifest.permission.ACCESS_FINE_LOCATION &&
                    grants.getOrNull(index) == PackageManager.PERMISSION_GRANTED
            }
            if (granted) plugin.start(requestId, pendingHighAccuracy && fineGranted)
            else plugin.failure(requestId, "PERMISSION_DENIED", "Location permission was denied.")
            return true
        }
    }

    @LynxMethod
    fun getCurrentPosition(requestId: String, highAccuracy: Boolean) {
        val host = activity
        if (host == null) {
            failure(requestId, "NO_ACTIVITY", "Geolocation requires an Android Activity host.")
            return
        }
        val fine = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_FINE_LOCATION) == PackageManager.PERMISSION_GRANTED
        val coarse = ContextCompat.checkSelfPermission(appContext, Manifest.permission.ACCESS_COARSE_LOCATION) == PackageManager.PERMISSION_GRANTED
        if (fine || coarse) {
            start(requestId, highAccuracy && fine)
            return
        }
        if (pending != null) {
            failure(requestId, "BUSY", "Another location permission request is already active.")
            return
        }
        pending = this
        pendingRequestId = requestId
        pendingHighAccuracy = highAccuracy
        host.requestPermissions(arrayOf(Manifest.permission.ACCESS_FINE_LOCATION, Manifest.permission.ACCESS_COARSE_LOCATION), LOCATION_REQUEST)
    }

    private fun start(requestId: String, highAccuracy: Boolean) {
        val manager = appContext.getSystemService(LocationManager::class.java)
        if (manager == null) {
            failure(requestId, "NOT_SUPPORTED", "Location services are unavailable.")
            return
        }
        val provider = when {
            highAccuracy && manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            manager.isProviderEnabled(LocationManager.NETWORK_PROVIDER) -> LocationManager.NETWORK_PROVIDER
            manager.isProviderEnabled(LocationManager.GPS_PROVIDER) -> LocationManager.GPS_PROVIDER
            else -> null
        }
        if (provider == null) {
            failure(requestId, "POSITION_UNAVAILABLE", "Enable Location services and try again.")
            return
        }
        val last = try { manager.getLastKnownLocation(provider) } catch (_: SecurityException) { null }
        if (last != null) {
            emitLocation(requestId, last)
            return
        }
        val handler = Handler(Looper.getMainLooper())
        var completed = false
        lateinit var timeout: Runnable
        val listener = object : LocationListener {
            override fun onLocationChanged(location: Location) {
                if (completed) return
                completed = true
                manager.removeUpdates(this)
                handler.removeCallbacks(timeout)
                emitLocation(requestId, location)
            }

            override fun onProviderDisabled(provider: String) {
                if (completed) return
                completed = true
                manager.removeUpdates(this)
                handler.removeCallbacks(timeout)
                failure(requestId, "POSITION_UNAVAILABLE", "Location provider was disabled.")
            }

            @Deprecated("Deprecated in Android.")
            override fun onStatusChanged(provider: String?, status: Int, extras: Bundle?) = Unit
        }
        timeout = Runnable {
            if (!completed) {
                completed = true
                manager.removeUpdates(listener)
                failure(requestId, "TIMEOUT", "Location did not arrive within 30 seconds.")
            }
        }
        try {
            @Suppress("DEPRECATION") manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
            handler.postDelayed(timeout, 30_000L)
        } catch (_: SecurityException) {
            failure(requestId, "PERMISSION_DENIED", "Location permission is not available.")
        }
    }

    private fun emitLocation(requestId: String, location: Location) {
        success(requestId, JavaOnlyMap().apply {
            putDouble("latitude", location.latitude)
            putDouble("longitude", location.longitude)
            putDouble("accuracy", location.accuracy.toDouble())
            putDouble("timestamp", location.time.toDouble())
            putString("provider", location.provider ?: "unknown")
        })
    }
}
