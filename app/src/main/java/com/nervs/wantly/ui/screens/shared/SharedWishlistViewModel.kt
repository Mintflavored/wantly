package com.nervs.wantly.ui.screens.shared

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.remote.ApiException
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.WishDto
import com.nervs.wantly.data.remote.dto.WishlistDto
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch

sealed interface SharedWishlistUiState {
    data object Loading : SharedWishlistUiState
    data class Loaded(
        val wishlist: WishlistDto,
        val wishes: List<WishDto>,
    ) : SharedWishlistUiState
    data object Error : SharedWishlistUiState
    data object NotFound : SharedWishlistUiState
}

/**
 * Загружает shared wishlist напрямую через API (не Room — shared список
 * может принадлежать другому пользователю и отсутствовать в локальной БД).
 * Работает без JWT — public endpoint.
 */
class SharedWishlistViewModel(
    private val token: String,
    private val api: WantlyApi,
) : ViewModel() {

    private val _state = MutableStateFlow<SharedWishlistUiState>(SharedWishlistUiState.Loading)
    val state = _state.asStateFlow()

    init {
        load()
    }

    fun load() {
        _state.value = SharedWishlistUiState.Loading
        viewModelScope.launch {
            try {
                val resp = api.getSharedWishlist(token)
                _state.value = SharedWishlistUiState.Loaded(resp.wishlist, resp.wishes)
            } catch (e: ApiException) {
                _state.value = if (e.code == 404) SharedWishlistUiState.NotFound else SharedWishlistUiState.Error
            } catch (e: Exception) {
                _state.value = SharedWishlistUiState.Error
            }
        }
    }
}
