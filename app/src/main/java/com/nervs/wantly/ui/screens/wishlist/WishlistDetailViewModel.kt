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
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

/**
 * Sealed UI state для WishlistDetailScreen.
 *
 * [Loading] показывается пока Room отдаёт первую эмиссию. [NotFound] —
 * wishlist == null (список не существует или был удалён). [Loaded] —
 * данные доступны. Empty wishes обрабатываются внутри [Loaded].
 *
 * До этого экран использовал `wishlist: StateFlow<WishlistEntity?>` с initial
 * `null` — невозможно было отличить «грузится» от «не существует», что
 * приводило к ложному «Список не найден» при каждом открытии (C2).
 */
sealed interface WishlistDetailUiState {
    data object Loading : WishlistDetailUiState
    data object NotFound : WishlistDetailUiState
    data class Loaded(
        val wishlist: WishlistEntity,
        val wishes: List<WishEntity>,
    ) : WishlistDetailUiState
}

class WishlistDetailViewModel(
    private val wishlistId: Long,
    private val repository: WishlistRepository,
    private val syncManager: SyncManager,
    private val api: WantlyApi,
) : ViewModel() {
    val state: StateFlow<WishlistDetailUiState> =
        combine(repository.observeWishlist(wishlistId), repository.observeWishes(wishlistId)) { w, ws ->
            if (w == null) WishlistDetailUiState.NotFound
            else WishlistDetailUiState.Loaded(w, ws)
        }.stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(5_000),
            WishlistDetailUiState.Loading,
        )

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

    /** Ошибка первичной загрузки token. До фикса isLoadingToken застревал true
     *  навсегда при ошибке, switch оставался disabled без объяснения (M5).
     *  Теперь UI показывает текст + retry. */
    private val _tokenLoadError = MutableStateFlow(false)
    val tokenLoadError: StateFlow<Boolean> = _tokenLoadError.asStateFlow()

    init {
        loadShareToken()
    }

    /** Загружает shareToken с сервера (Room не хранит его). Использует serverId
     *  (backend id), не wishlistId (local PK). Public — для retry из диалога. */
    fun loadShareToken() {
        viewModelScope.launch {
            _tokenLoadError.value = false
            _isLoadingToken.value = true
            // Раньше тут было `.first { it != null }` — для удалённого списка оно
            // подвисает навсегда (predicate не выполняется, flow не завершается).
            // `.first()` берёт первое значение (включая null) — не блокируется.
            val entity = repository.observeWishlist(wishlistId).first()
            if (entity == null) {
                _isLoadingToken.value = false
                return@launch
            }
            val serverId = entity.serverId
            if (serverId == null) {
                // Local-only/guest список — sharing недоступен.
                _isLoadingToken.value = false
                return@launch
            }
            runCatching { api.getWishlistDetail(serverId) }
                .onSuccess {
                    _shareToken.value = it.wishlist.shareToken
                    _isLoadingToken.value = false
                }
                .onFailure {
                    // Не блокируем UI молча — даём пользователю retry (M5).
                    _tokenLoadError.value = true
                    _isLoadingToken.value = false
                }
        }
    }

    /** Set isShared на сервере (не blind toggle — передаёт desired state).
     *  Использует serverId (backend id), не wishlistId.
     *  При успехе — обновляет shareToken (dialog синхронизируется через LaunchedEffect).
     *  При ошибке/нет serverId — increment toggleErrorCount → dialog разблокирует switch. */
    fun setShare(enabled: Boolean) {
        viewModelScope.launch {
            val entity = repository.observeWishlist(wishlistId).first()
            if (entity == null) {
                _toggleErrorCount.value++
                return@launch
            }
            val serverId = entity.serverId
            if (serverId == null) {
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
