package com.songladder.android.data.connectivity

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest

/**
 * Thin [ConnectivityManager.NetworkCallback] wrapper: the first connectivity-aware
 * code in this app. No WorkManager - this app has no other background-work
 * dependency, and a plain callback registered once from [com.songladder.android.data.AppContainer]
 * is consistent with its existing manual-DI, no-extra-frameworks style. Used only to
 * retry pending album matches when a connection becomes available again (see
 * [com.songladder.android.data.repository.DefaultAlbumRepository.retryPendingMatches]).
 */
class NetworkAvailabilityMonitor(context: Context) {
    private val connectivityManager =
        context.applicationContext.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    fun start(onAvailable: () -> Unit) {
        val manager = connectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                onAvailable()
            }
        }
        runCatching { manager.registerNetworkCallback(request, callback) }
    }
}
