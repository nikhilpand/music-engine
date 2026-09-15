package com.aurora.engine.player.android.network

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import java.util.concurrent.CopyOnWriteArrayList

enum class NetworkType {
    WIFI,
    CELLULAR,
    ETHERNET,
    OTHER,
    OFFLINE
}

data class NetworkStatus(
    val isConnected: Boolean,
    val networkType: NetworkType,
    val isMetered: Boolean
)

/**
 * Monitors network state and connectivity transitions.
 *
 * CRITICAL BEHAVIOR:
 * Handover between Wi-Fi and Cellular does NOT restart healthy streams.
 * If playback is active and buffered, it continues undisturbed. If a connection
 * drop causes a socket read failure, the recovery coordinator uses this monitor's
 * state to coordinate a seamless, position-preserving reconnect.
 */
class NetworkConnectivityMonitor(
    private val context: Context,
    private val connectivityManager: ConnectivityManager? =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
) {

    private val _networkStatus = MutableStateFlow(determineInitialStatus())
    val networkStatus: StateFlow<NetworkStatus> = _networkStatus.asStateFlow()

    private val listeners = CopyOnWriteArrayList<(NetworkStatus) -> Unit>()
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var isRegistered = false

    fun startMonitoring() {
        val cm = connectivityManager ?: return
        if (isRegistered) return

        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                updateStatus()
            }

            override fun onLost(network: Network) {
                updateStatus()
            }

            override fun onCapabilitiesChanged(network: Network, networkCapabilities: NetworkCapabilities) {
                updateStatus()
            }
        }

        try {
            cm.registerNetworkCallback(request, callback)
            networkCallback = callback
            isRegistered = true
            updateStatus()
        } catch (_: Exception) {
            // In headless/test environments where registerNetworkCallback may be constrained
        }
    }

    fun stopMonitoring() {
        val cm = connectivityManager ?: return
        val callback = networkCallback ?: return
        if (!isRegistered) return

        try {
            cm.unregisterNetworkCallback(callback)
        } catch (_: Exception) {
        } finally {
            networkCallback = null
            isRegistered = false
        }
    }

    fun addListener(listener: (NetworkStatus) -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: (NetworkStatus) -> Unit) {
        listeners.remove(listener)
    }

    fun isOnline(): Boolean = _networkStatus.value.isConnected

    /**
     * For manual injection or testing.
     */
    fun updateManually(status: NetworkStatus) {
        val old = _networkStatus.value
        if (old != status) {
            _networkStatus.value = status
            listeners.forEach { it(status) }
        }
    }

    private fun updateStatus() {
        val newStatus = determineCurrentStatus()
        val oldStatus = _networkStatus.value
        if (oldStatus != newStatus) {
            _networkStatus.value = newStatus
            listeners.forEach { it(newStatus) }
        }
    }

    private fun determineInitialStatus(): NetworkStatus {
        return determineCurrentStatus()
    }

    private fun determineCurrentStatus(): NetworkStatus {
        val cm = connectivityManager ?: return NetworkStatus(false, NetworkType.OFFLINE, false)
        val activeNetwork = try {
            cm.activeNetwork
        } catch (_: Exception) {
            null
        } ?: return NetworkStatus(false, NetworkType.OFFLINE, false)

        val caps = try {
            cm.getNetworkCapabilities(activeNetwork)
        } catch (_: Exception) {
            null
        } ?: return NetworkStatus(false, NetworkType.OFFLINE, false)

        val hasInternet = caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
        if (!hasInternet) {
            return NetworkStatus(false, NetworkType.OFFLINE, false)
        }

        val type = when {
            caps.hasTransport(NetworkCapabilities.TRANSPORT_WIFI) -> NetworkType.WIFI
            caps.hasTransport(NetworkCapabilities.TRANSPORT_CELLULAR) -> NetworkType.CELLULAR
            caps.hasTransport(NetworkCapabilities.TRANSPORT_ETHERNET) -> NetworkType.ETHERNET
            else -> NetworkType.OTHER
        }

        val isMetered = !caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_NOT_METERED)

        return NetworkStatus(
            isConnected = true,
            networkType = type,
            isMetered = isMetered
        )
    }
}
