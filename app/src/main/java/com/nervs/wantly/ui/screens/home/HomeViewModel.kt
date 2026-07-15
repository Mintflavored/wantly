package com.nervs.wantly.ui.screens.home

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.R
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.WishlistWithCount
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.repository.WishlistRepository
import com.nervs.wantly.ui.SnackbarController
import com.nervs.wantly.ui.SnackbarMessage
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.catch
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sealed UI state для HomeScreen.
 *
 * Empty не отдельное состояние — валидный результат внутри [Loaded]
 * (как в SharedWishlistUiState — эталонном sealed state проекта).
 * Loading показывается только пока Room отдаёт первую эмиссию.
 */
sealed interface HomeUiState {
    data object Loading : HomeUiState
    data class Loaded(val wishlists: List<WishlistWithCount>) : HomeUiState
    data object Error : HomeUiState
}

class HomeViewModel(
    private val repository: WishlistRepository,
    private val syncManager: SyncManager,
) : ViewModel() {
    val state: StateFlow<HomeUiState> =
        repository.observeWishlists()
            .map { list -> HomeUiState.Loaded(list) as HomeUiState }
            .catch { emit(HomeUiState.Error) }
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), HomeUiState.Loading)

    fun createWishlist(title: String, description: String?, coverColor: Int) {
        viewModelScope.launch {
            repository.createWishlist(title, description?.ifBlank { null }, coverColor)
            syncManager.pushPending()
            SnackbarController.send(SnackbarMessage(R.string.snackbar_list_created))
        }
    }

    /**
     * Soft-delete с undo: markDeleted → Snackbar с «Отменить».
     * Push откладывается до закрытия Snackbar:
     * - undo (ActionPerformed) → restoreDeleted, push НЕ запускается
     * - dismiss (таймаут/swipe) → pushPendingScoped отправляет tombstone
     */
    fun deleteWishlist(wishlist: WishlistEntity) {
        viewModelScope.launch {
            repository.deleteWishlist(wishlist)
            SnackbarController.send(
                SnackbarMessage(
                    messageRes = R.string.snackbar_list_deleted,
                    actionLabelRes = R.string.snackbar_action_undo,
                    onAction = {
                        // Восстанавливаем row. restoreDeleted возвращает synced
                        // из pre-delete снимка: если row был dirty (synced=0),
                        // он останется dirty — нужно запланировать push, иначе
                        // pending edit зависнет до следующего sync/logout.
                        repository.restoreWishlist(wishlist.id)
                        syncManager.pushPendingScoped()
                    },
                    onDismiss = {
                        // Окно undo закрыто: снимаем undoProtected (tombstone
                        // становится видимым для getPendingDelete), потом push.
                        repository.commitWishlistDelete(wishlist.id)
                        syncManager.pushPendingScoped()
                    },
                    duration = SnackbarDuration.Long,
                ),
            )
        }
    }
}
