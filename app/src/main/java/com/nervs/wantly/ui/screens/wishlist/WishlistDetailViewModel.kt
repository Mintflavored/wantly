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

    init {
        // Загружаем shareToken с сервера при открытии экрана (Room не хранит его).
        // Используем serverId (не wishlistId — это local PK, не совпадает с backend id).
        viewModelScope.launch {
            val entity = repository.observeWishlist(wishlistId).first { it != null } ?: return@launch
            val serverId = entity.serverId ?: return@launch
            runCatching { api.getWishlistDetail(serverId) }
                .onSuccess { _shareToken.value = it.wishlist.shareToken }
        }
    }

    /** Toggle isShared на сервере. Использует serverId (backend id), не wishlistId. */
    fun toggleShare(onDone: (String?) -> Unit = {}) {
        viewModelScope.launch {
            val entity = wishlist.first { it != null } ?: return@launch
            val serverId = entity.serverId ?: return@launch
            runCatching { api.toggleShare(serverId) }
                .onSuccess { dto ->
                    _shareToken.value = dto.shareToken
                    onDone(dto.shareToken)
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
