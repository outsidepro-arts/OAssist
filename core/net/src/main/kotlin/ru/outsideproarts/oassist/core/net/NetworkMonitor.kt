package ru.outsideproarts.oassist.core.net

import android.annotation.SuppressLint
import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import ru.outsideproarts.oassist.core.model.OAssistLog

/** Состояние default-сети приложения; неизвестное состояние допускает запрос. */
class NetworkMonitor(context: Context) {
    private val _isOnline = MutableStateFlow(true)
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val observer = context.applicationContext.getSystemService(ConnectivityManager::class.java)?.let { cm ->
        DefaultNetworkObserver(
            backend = AndroidDefaultNetworkBackend(cm),
            publish = { online ->
                if (_isOnline.value != online) {
                    OAssistLog.info(TAG, "Default-network availability changed: $online", null)
                }
                _isOnline.value = online
            },
            reportFailure = { operation, error ->
                OAssistLog.warn(TAG, "Network $operation failed (${error.javaClass.simpleName})", null)
            },
        )
    }

    fun start() {
        if (observer == null) {
            OAssistLog.warn(TAG, "ConnectivityManager unavailable; allowing requests", null)
            return
        }
        if (observer.start()) {
            OAssistLog.info(TAG, "Default-network monitor started; online=${_isOnline.value}", null)
        }
    }

    fun stop() {
        observer?.stop()
    }

    private companion object {
        const val TAG = "NetworkMonitor"
    }
}

@SuppressLint("MissingPermission")
private class AndroidDefaultNetworkBackend(
    private val connectivity: ConnectivityManager,
) : DefaultNetworkBackend<Network> {
    private val callbacks = mutableMapOf<DefaultNetworkEvents<Network>, ConnectivityManager.NetworkCallback>()

    override fun initialState(): InitialNetworkState {
        val network = connectivity.activeNetwork ?: return InitialNetworkState.OFFLINE
        val caps = connectivity.getNetworkCapabilities(network) ?: return InitialNetworkState.UNKNOWN
        return if (caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)) {
            InitialNetworkState.ONLINE
        } else {
            InitialNetworkState.OFFLINE
        }
    }

    override fun register(events: DefaultNetworkEvents<Network>) {
        // A failed unregister may leave a live callback. Retry its cleanup before replacing it.
        for ((oldEvents, oldCallback) in callbacks.toMap()) {
            try {
                connectivity.unregisterNetworkCallback(oldCallback)
            } catch (_: IllegalArgumentException) {
                // The system no longer has this callback.
            }
            callbacks.remove(oldEvents)
        }
        val callback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) = events.available(network)
            override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) =
                events.capabilities(network, caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET))
            override fun onBlockedStatusChanged(network: Network, blocked: Boolean) = events.blocked(network, blocked)
            override fun onLost(network: Network) = events.lost(network)
        }
        callbacks[events] = callback
        try {
            connectivity.registerDefaultNetworkCallback(callback)
        } catch (error: Exception) {
            try {
                connectivity.unregisterNetworkCallback(callback)
                callbacks.remove(events)
            } catch (_: IllegalArgumentException) {
                callbacks.remove(events)
            } catch (_: Exception) {
                // Keep the reference so a later start can retry cleanup.
            }
            throw error
        }
    }

    override fun unregister(events: DefaultNetworkEvents<Network>) {
        val callback = callbacks[events] ?: return
        try {
            connectivity.unregisterNetworkCallback(callback)
        } catch (_: IllegalArgumentException) {
            // The system no longer has this callback.
        }
        callbacks.remove(events)
    }
}
