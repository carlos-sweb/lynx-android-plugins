package dev.lynx.android.plugins.vibration

import android.content.Context
import android.os.Build
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule

/** Registers the bounded vibration bridge with a Lynx view. */
object LynxVibrationPlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxVibrationPlugin", VibrationPlugin::class.java)
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
