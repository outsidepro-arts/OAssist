package ru.outsideproarts.oassist.core.net

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Живое состояние сети: active-сеть с возможностью INTERNET.
 * Fail-open: до первого опроса и без ConnectivityManager — online (старое поведение).
 *
 * Регистрируется один раз из AppGraph (контекст приложения), живёт пока жив процесс.
 * Колбэки приходят на системном потоке — пишем только в потокобезопасный StateFlow.
 * Требуется `ACCESS_NETWORK_STATE` (normal-разрешение, без runtime-диалога).
 */
class NetworkMonitor(context: Context) {

    private val connectivity: ConnectivityManager? =
        context.applicationContext.getSystemService(ConnectivityManager::class.java)

    private val _isOnline = MutableStateFlow(queryOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        override fun onAvailable(network: Network) = refresh()
        override fun onLost(network: Network) = refresh()
        override fun onCapabilitiesChanged(network: Network, caps: NetworkCapabilities) = refresh()
        override fun onUnavailable() = refresh()
    }

    private var started = false

    // ACCESS_NETWORK_STATE объявлен в манифесте :app (lint модуля его не видит —
    // прецедент M2.7): разрешение normal, не отзывается; вызовы дополнительно в runCatching.
    @android.annotation.SuppressLint("MissingPermission")
    fun start() {
        if (started) return
        started = true
        refresh()
        val cm = connectivity ?: return
        runCatching {
            cm.registerNetworkCallback(
                NetworkRequest.Builder()
                    .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
                    .build(),
                callback,
            )
        }
    }

    fun stop() {
        val cm = connectivity ?: return
        runCatching { cm.unregisterNetworkCallback(callback) }
    }

    private fun refresh() {
        _isOnline.value = queryOnline()
    }

    // См. комментарий у start(): разрешение — в манифесте :app.
    @android.annotation.SuppressLint("MissingPermission")
    private fun queryOnline(): Boolean {
        val cm = connectivity ?: return true
        val caps = runCatching {
            cm.activeNetwork?.let { cm.getNetworkCapabilities(it) }
        }.getOrNull()
        return caps?.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) == true
    }
}
