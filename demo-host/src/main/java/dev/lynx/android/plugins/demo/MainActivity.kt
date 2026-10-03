package dev.lynx.android.plugins.demo

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import android.util.Log
import androidx.appcompat.app.AppCompatActivity
import com.lynx.tasm.LynxError
import com.lynx.tasm.LynxViewClient
import com.lynx.tasm.LynxViewBuilder
import com.lynx.xelement.XElementBehaviors
import dev.lynx.android.plugins.LynxAndroidPlugins

/** A deliberately small custom host: Lynx Go cannot ship third-party native modules. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val builder = LynxViewBuilder()
        builder.addBehaviors(XElementBehaviors().create())
        LynxAndroidPlugins.register(builder)
        builder.setTemplateProvider(AssetTemplateProvider(this))
        val lynxView = builder.build(this)
        lynxView.addLynxViewClient(object : LynxViewClient() {
            override fun onLoadSuccess() {
                Log.i("LynxDemo", "Bundle loaded successfully")
            }

            override fun onLoadFailed(error: String) {
                Log.e("LynxDemo", "Bundle load failed: $error")
            }

            @Deprecated("Lynx legacy error callback")
            override fun onReceivedError(error: LynxError) {
                Log.e("LynxDemo", "Lynx error: $error")
            }
        })
        setContentView(lynxView)
        lynxView.renderTemplateUrl("main-thread.bundle", "")
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        if (!LynxAndroidPlugins.onRequestPermissionsResult(requestCode, permissions, grantResults)) {
            super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        }
    }

    @Deprecated("Deprecated in Android; needed to receive the external camera intent result.")
    override fun onActivityResult(requestCode: Int, resultCode: Int, data: Intent?) {
        if (!LynxAndroidPlugins.onActivityResult(requestCode, resultCode, data)) {
            super.onActivityResult(requestCode, resultCode, data)
        }
    }
}
