package com.aiglobal.jobfinder.data.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities

/**
 * Answers one question: can the device reach the internet right now? It exists so a
 * refresh with no connection can say "waiting for network" instead of reporting every
 * source as broken, which is the difference between an actionable message and a
 * misleading one.
 */
class NetworkMonitor(private val context: Context) {

    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun isOnline(): Boolean {
        val manager = connectivityManager ?: return true
        val network: Network = manager.activeNetwork ?: return false
        val capabilities = manager.getNetworkCapabilities(network) ?: return false
        return capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
