package com.nervs.wantly.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.local.WishlistWithCount
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class HomeViewModel(private val repository: WishlistRepository) : ViewModel() {
    val wishlists: StateFlow<List<WishlistWithCount>> =
        repository.observeWishlists()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    fun createWishlist(title: String, description: String?, coverColor: Int) {
        viewModelScope.launch {
            repository.createWishlist(title, description?.ifBlank { null }, coverColor)
        }
    }

    fun deleteWishlist(wishlist: WishlistEntity) {
        viewModelScope.launch { repository.deleteWishlist(wishlist) }
    }
}
