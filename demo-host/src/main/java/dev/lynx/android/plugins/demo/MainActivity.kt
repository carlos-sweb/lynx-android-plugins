package dev.lynx.android.plugins.demo

import android.content.Intent
import android.content.pm.PackageManager
import android.os.Bundle
import androidx.appcompat.app.AppCompatActivity
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.LynxAndroidPlugins

/** A deliberately small custom host: Lynx Go cannot ship third-party native modules. */
class MainActivity : AppCompatActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        val builder = LynxViewBuilder()
        LynxAndroidPlugins.register(builder)
        val lynxView = builder.build(this)
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
