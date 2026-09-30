package dev.lynx.android.plugins.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.NetworkCapabilities
import com.lynx.jsbridge.LynxMethod
import com.lynx.react.bridge.JavaOnlyMap
import com.lynx.tasm.LynxViewBuilder
import dev.lynx.android.plugins.core.PluginModule

/** Registers the network-state bridge with a Lynx view. */
object LynxNetworkPlugin {
    fun register(builder: LynxViewBuilder) {
        builder.registerModule("LynxNetworkPlugin", NetworkPlugin::class.java)
    }
}

/** Network transport/capability snapshot; it intentionally exposes no SSID, BSSID, or IP address. */
class NetworkPlugin(context: Context) : PluginModule(context, "lynxAndroidPlugins:network") {
    @LynxMethod
    fun getInfo(requestId: String) {
        val connectivity = appContext.getSystemService(ConnectivityManager::class.java)
        val capabilities = connectivity?.activeNetwork?.let(connectivity::getNetworkCapabilities)
        val online = capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        success(requestId, JavaOnlyMap().apply {
            putBoolean("online", online)
            putString("type", transportOf(capabilities))
            putBoolean("metered", connectivity?.isActiveNetworkMetered == true)
            putBoolean("validated", capabilities?.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED) == true)
        })
    }

    private fun transportOf(capabilities: NetworkCapabilities?): String = when {
        capabilities == null -> "none"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> "wifi"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> "cellular"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> "ethernet"
        capabilities.hasTransport(NetworkCapabilities.TRANSPORT_VPN) -> "vpn"
        else -> "other"
    }
}
