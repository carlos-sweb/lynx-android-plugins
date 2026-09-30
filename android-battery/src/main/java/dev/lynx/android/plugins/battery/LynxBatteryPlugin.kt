package dev.lynx.android.plugins.battery

import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule

/** Registers the Battery Status bridge with a Lynx view. */
object LynxBatteryPlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxBatteryPlugin", BatteryPlugin::class.java)
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
