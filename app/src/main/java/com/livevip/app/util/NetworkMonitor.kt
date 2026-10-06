package com.livevip.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Lightweight connectivity helper: current availability,
 * transport type, and loss/recovery callbacks.
 */
class NetworkMonitor(context: Context) {

    enum class Transport { WIFI, CELLULAR, OTHER, NONE }

    private val cm = context.applicationContext
        .getSystemService(Context.CONNECTIVITY_SERVICE) as ConnectivityManager

    var onAvailable: (() -> Unit)? = null
    var onLost: (() -> Unit)? = null

    private var registered = false
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) {
            onAvailable?.invoke()
        }

        override fun onLost(network: Network) {
            onLost?.invoke()
        }
    }

    fun isOnline(): Boolean = currentTransport() != Transport.NONE

    fun currentTransport(): Transport {
        val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return Transport.NONE
        if (!caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) return Transport.NONE
        return when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> Transport.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> Transport.CELLULAR
            else -> Transport.OTHER
        }
    }

    fun register() {
        if (registered) return
        try {
            cm.registerNetworkCallback(NetworkRequest.Builder().build(), callback)
            registered = true
        } catch (_: Exception) {
            // Too many callbacks registered on some OEM devices — non fatal.
        }
    }

    fun unregister() {
        if (!registered) return
        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            registered = false
        }
    }
}
