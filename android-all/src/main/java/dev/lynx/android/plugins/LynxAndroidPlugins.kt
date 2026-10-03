package dev.lynx.android.plugins

import android.content.Intent
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.battery.LynxBatteryPlugin
import dev.lynx.android.plugins.camera.LynxCameraPlugin
import dev.lynx.android.plugins.device.LynxDevicePlugin
import dev.lynx.android.plugins.geolocation.LynxGeolocationPlugin
import dev.lynx.android.plugins.network.LynxNetworkPlugin
import dev.lynx.android.plugins.vibration.LynxVibrationPlugin
import dev.lynx.android.plugins.maps.LynxMapsPlugin
import dev.lynx.android.plugins.sqlite.LynxSqlitePlugin

/**
 * Convenience registry for applications that deliberately use every plugin.
 *
 * Prefer the individual feature artifacts in production applications. They
 * keep the manifest and runtime surface limited to the selected capabilities.
 */
object LynxAndroidPlugins {
    fun register(builder: LynxViewBuilder) {
        LynxBatteryPlugin.register(builder)
        LynxCameraPlugin.register(builder)
        LynxDevicePlugin.register(builder)
        LynxGeolocationPlugin.register(builder)
        LynxNetworkPlugin.register(builder)
        LynxVibrationPlugin.register(builder)
        LynxMapsPlugin.register(builder)
        LynxSqlitePlugin.register(builder)
    }

    /** Returns true when any registered plugin consumed the permission result. */
    fun onRequestPermissionsResult(
        requestCode: Int,
        permissions: Array<out String>,
        grantResults: IntArray,
    ): Boolean = LynxGeolocationPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults) ||
        LynxMapsPlugin.onRequestPermissionsResult(requestCode, permissions, grantResults)

    /** Returns true when any registered plugin consumed the Activity result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        LynxCameraPlugin.onActivityResult(requestCode, resultCode, data)
}
