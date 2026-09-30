package dev.lynx.android.plugins.demo

import android.app.Application
import com.facebook.drawee.backends.pipeline.Fresco
import com.lynx.service.image.LynxImageService
import com.lynx.tasm.service.LynxServiceCenter
import com.lynx.tasm.LynxEnv

class DemoApplication : Application() {
    override fun onCreate() {
        super.onCreate()
        Fresco.initialize(this)
        LynxServiceCenter.inst().registerService(LynxImageService.getInstance())
        LynxEnv.inst().init(this, null, null, null)
    }
}
