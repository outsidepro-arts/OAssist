package ru.outsideproarts.oassist.core.net

internal enum class InitialNetworkState {
    ONLINE,
    OFFLINE,
    UNKNOWN,
}

internal interface DefaultNetworkEvents<N : Any> {
    fun available(network: N)
    fun capabilities(network: N, hasInternet: Boolean)
    fun blocked(network: N, blocked: Boolean)
    fun lost(network: N)
}

internal interface DefaultNetworkBackend<N : Any> {
    fun initialState(): InitialNetworkState
    fun register(events: DefaultNetworkEvents<N>)
    fun unregister(events: DefaultNetworkEvents<N>)
}

/** Serializes network transitions and ignores events from obsolete registrations. */
internal class DefaultNetworkObserver<N : Any>(
    private val backend: DefaultNetworkBackend<N>,
    private val publish: (Boolean) -> Unit,
    private val reportFailure: (String, Throwable) -> Unit,
) {
    private val lock = Any()
    private var listener: DefaultNetworkEvents<N>? = null
    private var currentNetwork: N? = null
    private var hasInternet: Boolean? = null
    private var blocked: Boolean? = null

    fun start(): Boolean = synchronized(lock) {
        if (listener != null) return@synchronized true
        val initial = try {
            backend.initialState()
        } catch (error: Exception) {
            reportFailure("snapshot", error)
            InitialNetworkState.UNKNOWN
        }
        publish(initial != InitialNetworkState.OFFLINE)
        val events = object : DefaultNetworkEvents<N> {
            override fun available(network: N) = synchronized(lock) {
                if (listener !== this) return@synchronized
                if (currentNetwork != network) {
                    currentNetwork = network
                    hasInternet = null
                    blocked = null
                }
            }

            override fun capabilities(network: N, hasInternet: Boolean) = synchronized(lock) {
                if (listener !== this || currentNetwork != network) return@synchronized
                this@DefaultNetworkObserver.hasInternet = hasInternet
                publishIfReady()
            }

            override fun blocked(network: N, blocked: Boolean) = synchronized(lock) {
                if (listener !== this || currentNetwork != network) return@synchronized
                this@DefaultNetworkObserver.blocked = blocked
                publishIfReady()
            }

            override fun lost(network: N) = synchronized(lock) {
                if (listener !== this || currentNetwork != network) return@synchronized
                clearNetwork()
                publish(false)
            }
        }
        listener = events
        try {
            backend.register(events)
            true
        } catch (error: Exception) {
            listener = null
            clearNetwork()
            publish(true)
            reportFailure("registration", error)
            false
        }
    }

    fun stop() = synchronized(lock) {
        val current = listener ?: return@synchronized
        listener = null
        clearNetwork()
        try {
            backend.unregister(current)
        } catch (error: Exception) {
            reportFailure("unregistration", error)
        }
    }

    private fun publishIfReady() {
        val internet = hasInternet ?: return
        val isBlocked = blocked ?: return
        publish(internet && !isBlocked)
    }

    private fun clearNetwork() {
        currentNetwork = null
        hasInternet = null
        blocked = null
    }
}
