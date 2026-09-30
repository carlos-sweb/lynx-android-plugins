package dev.lynx.android.plugins.demo

import android.app.Application
import com.lynx.tasm.LynxEnv

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        LynxEnv.inst().init(this, null, null, null)
    }
}
