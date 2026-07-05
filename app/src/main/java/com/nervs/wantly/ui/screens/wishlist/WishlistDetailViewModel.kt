package com.nervs.wantly.ui.screens.wishlist

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.model.WishStatus
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class WishlistDetailViewModel(
    private val wishlistId: Long,
    private val repository: WishlistRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    val wishlist: StateFlow<WishlistEntity?> =
        repository.observeWishlist(wishlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), null)

    val wishes: StateFlow<List<WishEntity>> =
        repository.observeWishes(wishlistId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

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
