package dev.lynx.android.plugins.demo

import android.content.Context
import com.lynx.tasm.provider.AbsTemplateProvider
import java.io.IOException

/** Loads demo bundles from Android assets without blocking the UI thread. */
class AssetTemplateProvider(private val context: Context) : AbsTemplateProvider() {
    override fun loadTemplate(url: String, callback: Callback) {
        Thread {
            try {
                callback.onSuccess(context.assets.open(url).use { it.readBytes() })
            } catch (error: IOException) {
                callback.onFailed(error.toString())
            }
        }.start()
    }
}
