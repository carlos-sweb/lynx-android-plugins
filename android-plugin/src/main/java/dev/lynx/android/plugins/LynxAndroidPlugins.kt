package dev.lynx.android.plugins

import android.Manifest
import android.app.Activity
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.location.Location
import android.location.LocationListener
import android.location.LocationManager
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import android.net.Uri
import android.os.BatteryManager
import android.os.Build
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.provider.MediaStore
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import com.lynx.jsbridge.LynxMethod
import com.lynx.jsbridge.LynxModule
import com.lynx.react.bridge.JavaOnlyArray
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.behavior.LynxContext
import com.lynx.tasm.LynxViewBuilder
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Registers the six Android-only modules and receives Activity callbacks.
 *
 * Hosts call [register] while configuring `LynxViewBuilder`, then forward the
 * two Activity callbacks below. This deliberately keeps each JS bridge small:
 * permission UI and camera results stay owned by Android, while results return
 * through Lynx's GlobalEventEmitter.
 */
object LynxAndroidPlugins {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxBatteryPlugin", BatteryPlugin::class.java)
        builder.registerModule("LynxCameraPlugin", CameraPlugin::class.java)
        builder.registerModule("LynxDevicePlugin", DevicePlugin::class.java)
        builder.registerModule("LynxGeolocationPlugin", GeolocationPlugin::class.java)
        builder.registerModule("LynxNetworkPlugin", NetworkPlugin::class.java)
        builder.registerModule("LynxVibrationPlugin", VibrationPlugin::class.java)
    }

    /** Return true when a plugin consumed this result. */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean = GeolocationPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults)

    /** Return true when a plugin consumed this result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        CameraPlugin.onActivityResult(requestCode, resultCode)
}

abstract class PluginModule(private val moduleContext: Context, private val eventName: String) : LynxModule(moduleContext) {
    protected val appContext = moduleContext.applicationContext
    protected val activity: Activity?
        get() = (moduleContext as? LynxContext)?.activity

    protected fun success(requestId: String, data: JavaOnlyMap = JavaOnlyMap()) {
        emit(requestId, true, data, null)
    }

    protected fun failure(requestId: String, code: String, message: String) {
        val data = JavaOnlyMap().apply {
            putString("code", code)
            putString("message", message)
        }
        emit(requestId, false, data, code)
    }

    private fun emit(requestId: String, ok: Boolean, data: JavaOnlyMap, errorCode: String?) {
        val envelope = JavaOnlyMap().apply {
            putString("requestId", requestId)
            putBoolean("ok", ok)
            putMap("data", data)
            if (errorCode != null) putString("errorCode", errorCode)
        }
        (moduleContext as? LynxContext)?.sendGlobalEvent(eventName, JavaOnlyArray.of(envelope))
    }
}

/** Minimal implementation of the W3C Battery Status data shape. */
class BatteryPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:battery") {
    @LynxMethod
    fun getStatus(requestId: String) {
        val manager = appContext.getSystemService(BatteryManager::class.java)
        val battery = appContext.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        val status = battery?.getIntExtra(BatteryManager.EXTRA_STATUS, -1) ?: -1
        val plugged = battery?.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) ?: 0
        val rawLevel = manager?.getIntProperty(BatteryManager.BATTERY_PROPERTY_CAPACITY) ?: -1
        val scale = battery?.getIntExtra(BatteryManager.EXTRA_SCALE, -1) ?: -1
        val level = when {
            rawLevel in 0..100 -> rawLevel / 100.0
            scale > 0 -> (battery?.getIntExtra(BatteryManager.EXTRA_LEVEL, 0) ?: 0).toDouble() / scale
            else -> -1.0
        }
        success(requestId, JavaOnlyMap().apply {
            putDouble("level", level)
            putBoolean("charging", status == BatteryManager.BATTERY_STATUS_CHARGING || status == BatteryManager.BATTERY_STATUS_FULL)
            putBoolean("chargingTimeKnown", plugged != 0)
            putBoolean("dischargingTimeKnown", plugged == 0)
        })
    }
}

/** Safe, non-identifying device information. No serial, Android ID, IMEI or advertising ID. */
class DevicePlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:device") {
    @LynxMethod
    fun getInfo(requestId: String) {
        success(requestId, JavaOnlyMap().apply {
            putString("platform", "android")
            putString("manufacturer", Build.MANUFACTURER ?: "unknown")
            putString("model", Build.MODEL ?: "unknown")
            putString("brand", Build.BRAND ?: "unknown")
            putString("osVersion", Build.VERSION.RELEASE ?: "unknown")
            putDouble("apiLevel", Build.VERSION.SDK_INT.toDouble())
        })
    }
}

/** Network transport/capability snapshot; it intentionally exposes no SSID, BSSID or IP address. */
class NetworkPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:network") {
    @LynxMethod
    fun getInfo(requestId: String) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        success(requestId, JavaOnlyMap().apply {
            putBoolean("online", online)
            putString("type", transportOf(capabilities))
            putBoolean("metered", connectivity?.isActiveNetworkMetered == true)
            putBoolean("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        })
    }

    private fun transportOf(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "none"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
}

/** One bounded vibration. Android silently ignores unsupported hardware. */
class VibrationPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:vibration") {
    @LynxMethod
    fun vibrate(requestId: String, durationMs: Double) {
        val duration = durationMs.toLong().coerceIn(1L, 10_000L)
        val vibrator: Vibrator? = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
            appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        } else {
            @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        }
        if (vibrator == null || !vibrator.hasVibrator()) {
            failure(requestId, "NOT_SUPPORTED", "This device has no vibration motor.")
            return
        }
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) vibrator.vibrate(VibrationEffect.createOneShot(duration, VibrationEffect.DEFAULT_AMPLITUDE))
        else @Suppress("DEPRECATION") vibrator.vibrate(duration)
        success(requestId, JavaOnlyMap().apply { putDouble("durationMs", duration.toDouble()) })
    }

    @LynxMethod
    fun cancel(requestId: String) {
        val vibrator = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) appContext.getSystemService(VibratorManager::class.java)?.defaultVibrator
        else @Suppress("DEPRECATION") appContext.getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
        vibrator?.cancel()
        success(requestId)
    }
}

/**
 * Captures through the device's camera application and returns a content URI.
 * Returning a URI avoids putting image bytes on the Lynx bridge.
 */
class CameraPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:camera") {
    companion object {
        private const val CAMERA_REQUEST = 49871
        private var pending: CameraPlugin? = null
        private var pendingRequestId: String? = null
        private var pendingUri: Uri? = null

        internal fun onActivityResult(requestCode: Int, resultCode: Int): Boolean {
            if (requestCode != CAMERA_REQUEST) return false
            val plugin = pending ?: return true
            val requestId = pendingRequestId ?: return true
            if (resultCode == Activity.RESULT_OK && pendingUri != null) {
                plugin.success(requestId, JavaOnlyMap().apply {
                    putString("uri", pendingUri.toString())
                    putString("mimeType", "image/jpeg")
                })
            } else {
                plugin.failure(requestId, "CANCELED", "Photo capture was canceled.")
            }
            pending = null
            pendingRequestId = null
            pendingUri = null
            return true
        }
    }

    @LynxMethod
    fun takePhoto(requestId: String) {
        val host = activity
        if (host == null) {
            failure(requestId, "NO_ACTIVITY", "Camera requires an Android Activity host.")
            return
        }
        if (pending != null) {
            failure(requestId, "BUSY", "Another camera request is already active.")
            return
        }
        val image = File(appContext.cacheDir, "lynx-android-plugins/camera/${timestamp()}.jpg").apply { parentFile?.mkdirs() }
        val uri = FileProvider.getUriForFile(appContext, "${appContext.packageName}.lynx_android_plugins.fileprovider", image)
        val intent = Intent(MediaStore.ACTION_IMAGE_CAPTURE).apply {
            putExtra(MediaStore.EXTRA_OUTPUT, uri)
            addFlags(Intent.FLAG_GRANT_WRITE_URI_PERMISSION or Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        if (intent.resolveActivity(host.packageManager) == null) {
            failure(requestId, "NOT_SUPPORTED", "No camera application is available.")
            return
        }
        pending = this
        pendingRequestId = requestId
        pendingUri = uri
        @Suppress("DEPRECATION") host.startActivityForResult(intent, CAMERA_REQUEST)
    }

    private fun timestamp(): String = SimpleDateFormat("yyyyMMdd-HHmmssSSS", Locale.US).format(Date())
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
            manager.requestSingleUpdate(provider, listener, Looper.getMainLooper())
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
