package dev.lynx.android.plugins.maps

import com.lynx.tasm.LynxViewBuilder
import com.lynx.tasm.behavior.Behavior
import com.lynx.tasm.behavior.LynxContext

/** Registers the Android-only map element with a Lynx host. */
object LynxMapsPlugin {
    /** Forward the host Activity's runtime-permission callback. */
    fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray): Boolean =
        MapLocation.onRequestPermissionsResult(requestCode)

    fun register(builder: LynxViewBuilder) {
        builder.addBehavior(object : Behavior("lynx-android-map") {
            override fun createUI(context: LynxContext): LynxMapsView = LynxMapsView(context)
        })
    }
}
