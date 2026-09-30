package dev.lynx.android.plugins.core

import android.app.Activity
import android.content.Context
import com.lynx.jsbridge.LynxModule
import com.lynx.react.bridge.JavaOnlyArray
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.behavior.LynxContext

/** Shared event bridge for Android plugin modules.
 *
 * Native methods return results through the Lynx GlobalEventEmitter. The
 * request ID lets a JS facade correlate immediate calls, permission requests,
 * and Activity results without relying on a different callback mechanism.
 */
abstract class PluginModule(
    private val moduleContext: Context,
    private val eventName: String,
) : LynxModule(moduleContext) {
    protected val appContext: Context = moduleContext.applicationContext
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
