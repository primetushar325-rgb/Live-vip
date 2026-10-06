package com.livevip.app.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import java.util.concurrent.atomic.AtomicBoolean

class NetworkController(context: Context, private val onAvailable: () -> Unit, private val onLost: () -> Unit) {
    private val connectivity = context.getSystemService(ConnectivityManager::class.java)
    private val registered = AtomicBoolean(false)
    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = onAvailable()
        override fun onLost(network: Network) = onLost()
    }

    fun isUsable(): Boolean = connectivity.activeNetwork?.let { network ->
        connectivity.getNetworkCapabilities(network)?.let {
            it.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) && it.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    } ?: false

    fun start() {
        if (registered.compareAndSet(false, true)) {
            connectivity.registerDefaultNetworkCallback(callback)
        }
    }

    fun stop() {
        if (registered.compareAndSet(true, false)) runCatching { connectivity.unregisterNetworkCallback(callback) }
    }
}
