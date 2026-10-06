package com.livevip.app.util

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import java.net.InetSocketAddress
import java.net.Socket
import javax.net.ssl.SSLSocketFactory

/**
 * Lightweight connectivity helper: current availability,
 * transport type, loss/recovery callbacks, honest upload estimate
 * and RTMP-host latency probe (used by the pre-flight network check).
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

    /**
     * Honest upload estimate from Android's link properties. This is what the
     * OS reports about the radio — it can be inaccurate on some networks.
     * Returns null when no honest estimate exists (the UI then says
     * "estimate unavailable — monitored live" instead of inventing a number).
     */
    fun estimatedUploadKbps(): Long? {
        return try {
            val caps = cm.getNetworkCapabilities(cm.activeNetwork) ?: return null
            val up = caps.linkUpstreamBandwidthKbps
            if (up > 0) up.toLong() else null
        } catch (_: Throwable) {
            null
        }
    }

    /**
     * Measure TCP handshake latency to a streaming host (rtmp/rtmps).
     * Real measurement, no data is sent beyond the handshake.
     * @return rttMillis or null when unreachable.
     */
    fun probeHostLatency(url: String): Long? {
        return try {
            val parsed = java.net.URI(url)
            val host = parsed.host ?: return null
            val port = when {
                parsed.port > 0 -> parsed.port
                parsed.scheme.equals("rtmps", true) -> 443
                else -> 1935
            }
            val start = System.nanoTime()
            val useTls = parsed.scheme.equals("rtmps", true) || port == 443
            val socket = if (useTls) {
                SSLSocketFactory.getDefault().createSocket() as Socket
            } else Socket()
            try {
                socket.soTimeout = 5000
                socket.connect(InetSocketAddress(host, port), 5000)
                ((System.nanoTime() - start) / 1_000_000L).coerceAtLeast(1L)
            } finally {
                try {
                    socket.close()
                } catch (_: Throwable) {
                }
            }
        } catch (_: Throwable) {
            null
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
