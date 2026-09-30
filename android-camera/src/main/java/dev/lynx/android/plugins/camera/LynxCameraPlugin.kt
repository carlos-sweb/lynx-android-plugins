package dev.lynx.android.plugins.camera

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.provider.MediaStore
import androidx.core.content.FileProvider
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/** Registers the camera bridge and forwards its external Activity result. */
object LynxCameraPlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxCameraPlugin", CameraPlugin::class.java)
    }

    /** Returns true when the camera bridge consumed the result. */
    fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?): Boolean =
        CameraPlugin.onActivityResult(requestCode, resultCode)
}

/**
 * Captures through the device camera application and returns a content URI.
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
