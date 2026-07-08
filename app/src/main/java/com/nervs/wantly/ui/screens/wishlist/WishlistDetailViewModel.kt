package com.nervs.wantly.ui.screens.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.model.WishStatus
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WishlistDetailViewModel(
    private val wishlistId: Long,
    private val repository: WishlistRepository,
    private val syncManager: SyncManager,
    private val api: WantlyApi,
) : ViewModel() {
    val wishlist: StateFlow<WishlistEntity?> =
        repository.observeWishlist(wishlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val wishes: StateFlow<List<WishEntity>> =
        repository.observeWishes(wishlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    /** Текущий share-token (transient — не хранится в Room). null если не shared
     *  или если ещё не загружен с сервера. */
    private val _shareToken = MutableStateFlow<String?>(null)
    val shareToken: StateFlow<String?> = _shareToken.asStateFlow()

    /** Счётчик ошибок toggle — dialog использует его для разблокировки switch при ошибке. */
    private val _toggleErrorCount = MutableStateFlow(0)
    val toggleErrorCount: StateFlow<Int> = _toggleErrorCount.asStateFlow()

    /** true пока initial token-load не завершён. Dialog блокирует switch на это время,
     *  чтобы не togg'нуть уже-shared список (isShared в Room может быть устаревшим). */
    private val _isLoadingToken = MutableStateFlow(true)
    val isLoadingToken: StateFlow<Boolean> = _isLoadingToken.asStateFlow()

    init {
        // Загружаем shareToken с сервера при открытии экрана (Room не хранит его).
        // Используем serverId (не wishlistId — это local PK, не совпадает с backend id).
        viewModelScope.launch {
            val entity = repository.observeWishlist(wishlistId).first { it != null } ?: run {
                // Local-only список без serverId — sharing недоступен.
                _isLoadingToken.value = false
                return@launch
            }
            val serverId = entity.serverId ?: run {
                _isLoadingToken.value = false
                return@launch
            }
            runCatching { api.getWishlistDetail(serverId) }
                .onSuccess {
                    _shareToken.value = it.wishlist.shareToken
                    _isLoadingToken.value = false
                }
                .onFailure {
                    // Ошибка загрузки — НЕ разблокируем switch: мы не знаем текущий
                    // share-state, toggle blind привёл бы к ревока активного share-link.
                    // isLoadingToken остаётся true → switch disabled, user видит loading.
                    // Retry доступен через re-fetch при следующем открытии экрана.
                }
        }
    }

    /** Set isShared на сервере (не blind toggle — передаёт desired state).
     *  Использует serverId (backend id), не wishlistId.
     *  При успехе — обновляет shareToken (dialog синхронизируется через LaunchedEffect).
     *  При ошибке/нет serverId — increment toggleErrorCount → dialog разблокирует switch. */
    fun setShare(enabled: Boolean) {
        viewModelScope.launch {
            val entity = wishlist.first { it != null } ?: run {
                _toggleErrorCount.value++
                return@launch
            }
            val serverId = entity.serverId ?: run {
                // Local-only/guest список — sharing невозможен без serverId.
                _toggleErrorCount.value++
                return@launch
            }
            runCatching { api.setShare(serverId, enabled) }
                .onSuccess { dto ->
                    _shareToken.value = dto.shareToken
                }
                .onFailure {
                    _toggleErrorCount.value++
                }
        }
    }

    fun cycleStatus(wish: WishEntity) {
        viewModelScope.launch {
            repository.updateWishStatus(
                wish,
                WishStatus.next(WishStatus.fromName(wish.status)),
            )
            // appScope: push не должен отменяться при popBackStack экрана
            syncManager.pushPendingScoped()
        }
    }

    /** Редактирование названия/описания/цвета списка → локально + push PATCH. */
    fun updateWishlist(wishlist: WishlistEntity, title: String, description: String?, coverColor: Int) {
        viewModelScope.launch {
            repository.updateWishlist(wishlist, title, description, coverColor)
            syncManager.pushPendingScoped()
        }
    }

    fun deleteWish(wish: WishEntity) {
        viewModelScope.launch {
            repository.deleteWish(wish)
            syncManager.pushPendingScoped()
        }
    }
}
