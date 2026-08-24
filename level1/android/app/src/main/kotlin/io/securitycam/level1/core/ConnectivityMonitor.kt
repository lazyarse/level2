package io.securitycam.level1.core

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Process-wide online/offline signal for the UI (outbox depth hints, queued
 * badges). Actual delivery scheduling is WorkManager's job via network
 * constraints — this monitor only informs, it never gates.
 */
object ConnectivityMonitor {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    @Volatile
    private var registered = false

    fun start(context: Context) {
        if (registered) return
        val cm =
            context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                _isOnline.value = true
            }

            override fun onLost(network: Network) {
                // Conservative: with no callback handle per-network tracking,
                // assume a loss means offline until the next availability.
                _isOnline.value = false
            }
        }
        runCatching { cm.registerNetworkCallback(request, callback) }
        registered = true
    }
}
