package com.coucou.android.app

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import com.coucou.android.link.LinkHealthText

/**
 * Tells the model when the network changes (a new Wi-Fi, a hotspot) and whether a Wi-Fi or cable is up at all.
 * Registered only while a computer is paired; the system calls back on a change, nothing polls.
 */
class NetworkWatch(
    context: Context,
    private val onChange: (wifiUp: Boolean) -> Unit,
    /** A network became the default one (Wi-Fi joined, Wi-Fi to mobile data): the link may be on the old one and half dead. */
    private val onNewNetwork: () -> Unit = {},
) {
    private val cm = context.applicationContext.getSystemService(ConnectivityManager::class.java)
    private var callback: ConnectivityManager.NetworkCallback? = null

    @Volatile var wifiUp: Boolean = isWifiUp()
        private set

    fun isWifiUp(): Boolean {
        val n = cm.activeNetwork ?: return false
        val c = cm.getNetworkCapabilities(n) ?: return false
        return c.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) || c.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET)
    }

    fun isCellular(): Boolean {
        val n = cm.activeNetwork ?: return false
        return cm.getNetworkCapabilities(n)?.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) == true
    }

    /** What the phone has right now, for the sentence that says why the link is down. */
    fun net(): LinkHealthText.PhoneNet = when {
        isWifiUp() -> LinkHealthText.PhoneNet.WIFI
        isCellular() -> LinkHealthText.PhoneNet.CELLULAR
        cm.activeNetwork == null -> LinkHealthText.PhoneNet.OFFLINE
        else -> LinkHealthText.PhoneNet.WIFI // another kind (VPN, Bluetooth): do not blame the phone's network
    }

    @Synchronized
    fun start() {
        if (callback != null) return
        val cb = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) { changed(); onNewNetwork() }
            override fun onLost(network: Network) { changed() }
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) { changed() }
        }
        callback = cb
        runCatching { cm.registerDefaultNetworkCallback(cb) }
    }

    @Synchronized
    fun stop() {
        callback?.let { runCatching { cm.unregisterNetworkCallback(it) } }
        callback = null
    }

    private fun changed() {
        val up = isWifiUp()
        wifiUp = up
        onChange(up)
    }
}
