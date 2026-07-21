package com.nervs.wantly.data

import android.content.Context
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/**
 * Reactive online/offline статус приложения.
 *
 * Использует [ConnectivityManager.registerDefaultNetworkCallback] — отслеживает
 * любое изменение сети (WiFi/mobile/none). [isOnline] = true только когда есть
 * active network с NET_CAPABILITY_INTERNET и validated.
 *
 * В UI используется для sync-индикатора (CloudOff когда offline).
 * SyncManager работает независимо — даже offline он пытается отправить dirty
 * rows (быстро падает с ConnectException, row остаётся dirty для retry).
 */
class NetworkMonitor(context: Context) {

    private val connectivityManager =
        context.getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager

    private val _isOnline = MutableStateFlow(checkOnline())
    val isOnline: StateFlow<Boolean> = _isOnline.asStateFlow()

    private val callback = object : ConnectivityManager.NetworkCallback() {
        // НЕ override onAvailable: он срабатывает ДО validation. Раннее true
        // заставляло WantlyApp запустить reconnect sync до реального интернет-
        // соединения, и onCapabilitiesChanged(...VALIDATED) потом не эмитил
        // повторное true (StateFlow дедуплицирует) → sync не ретраился.
        // online статус определяется только в onCapabilitiesChanged (с проверкой
        // VALIDATED) и onLost.

        override fun onLost(network: Network) {
            // onLost вызывается при потере одной сети, но может остаться другая.
            _isOnline.value = checkOnline()
        }

        override fun onCapabilitiesChanged(network: Network, capabilities: NetworkCapabilities) {
            _isOnline.value = capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
                capabilities.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
        }
    }

    /** Регистрация callback'а. Вызывается из Application.onCreate. */
    fun register() {
        connectivityManager?.registerDefaultNetworkCallback(callback)
    }

    /** Отписка. Вызывается из Application.onTerminate (не гарантируется на Android). */
    fun unregister() {
        runCatching { connectivityManager?.unregisterNetworkCallback(callback) }
    }

    private fun checkOnline(): Boolean {
        val cm = connectivityManager ?: return true // нет CM → считаем онлайн (тесты)
        val network = cm.activeNetwork ?: return false
        val caps = cm.getNetworkCapabilities(network) ?: return false
        return caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET) &&
            caps.hasCapability(NetworkCapabilities.NET_CAPABILITY_VALIDATED)
    }
}
