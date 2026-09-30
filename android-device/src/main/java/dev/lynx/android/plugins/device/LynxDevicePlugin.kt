package dev.lynx.android.plugins.device

import android.content.Context
import android.os.Build
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule

/** Registers the safe device-information bridge with a Lynx view. */
object LynxDevicePlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxDevicePlugin", DevicePlugin::class.java)
    }
}

/** Safe, non-identifying device information. No serial, Android ID, IMEI, or advertising ID. */
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
