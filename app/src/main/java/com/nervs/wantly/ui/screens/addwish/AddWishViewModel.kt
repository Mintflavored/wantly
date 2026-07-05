package com.nervs.wantly.ui.screens.addwish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.FieldLimits
import com.nervs.wantly.data.GuestCounter
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.model.WishDraft
import com.nervs.wantly.data.remote.LinkPreviewError
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AddWishUiState(
    val url: String = "",
    val title: String = "",
    val description: String = "",
    val price: String = "",
    val currency: String = "RUB",
    val storeName: String = "",
    val imageUrl: String = "",
    val isParsing: Boolean = false,
    val error: LinkPreviewError? = null,
    /** true = редактируем существующий wish (prefill + update); false = создание. */
    val isEditMode: Boolean = false,
) {
    val canSave: Boolean get() = title.isNotBlank()
}

class AddWishViewModel(
    private val wishlistId: Long,
    private val repository: WishlistRepository,
    private val guestCounter: GuestCounter? = null,
    private val sessionManager: SessionManager? = null,
    private val syncManager: SyncManager? = null,
    /** id существующего wish для edit-mode; null = создание. */
    private val wishId: Long? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddWishUiState())
    val uiState = _uiState.asStateFlow()

    /** Оригинальный wish в edit-mode — нужен для сохранения id/serverId/status/. */
    private var originalWish: WishEntity? = null

    init {
        // Prefill в edit-mode. В create-mode остаётся пустая форма.
        if (wishId != null) {
            viewModelScope.launch {
                val wish = repository.getWish(wishId) ?: return@launch
                originalWish = wish
                _uiState.update {
                    it.copy(
                        url = wish.url.orEmpty(),
                        title = wish.title,
                        description = wish.description.orEmpty(),
                        price = wish.price?.toString().orEmpty(),
                        currency = wish.currency,
                        storeName = wish.storeName.orEmpty(),
                        imageUrl = wish.imageUrl.orEmpty(),
                        isEditMode = true,
                    )
                }
            }
        }
    }

    fun onUrlChange(v: String) = update { copy(url = FieldLimits.clamp(v, FieldLimits.URL_MAX)) }
    fun onTitleChange(v: String) = update { copy(title = FieldLimits.clamp(v, FieldLimits.WISH_TITLE_MAX)) }
    fun onDescriptionChange(v: String) =
        update { copy(description = FieldLimits.clamp(v, FieldLimits.WISH_DESCRIPTION_MAX)) }
    fun onPriceChange(v: String) = update {
        copy(price = v.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' })
    }
    fun onCurrencyChange(v: String) = update { copy(currency = v.take(3)) }
    fun onStoreChange(v: String) = update { copy(storeName = FieldLimits.clamp(v, FieldLimits.WISH_STORE_MAX)) }
    fun onImageUrlChange(v: String) = update { copy(imageUrl = FieldLimits.clamp(v, FieldLimits.URL_MAX)) }

    fun recognize() {
        val url = _uiState.value.url.trim()
        if (url.isBlank()) return
        update { copy(isParsing = true, error = null) }
        viewModelScope.launch {
            val loggedIn = sessionManager?.isLoggedIn?.first() ?: false
            val preview = repository.previewLink(url, isLoggedIn = loggedIn)
            _uiState.update { st ->
                st.copy(
                    isParsing = false,
                    error = if (!preview.success && preview.title == null) preview.error else null,
                    title = preview.title ?: st.title,
                    description = preview.description ?: st.description,
                    price = preview.price?.let { numberForField(it) } ?: st.price,
                    currency = preview.currency ?: st.currency,
                    storeName = preview.storeName ?: st.storeName,
                    imageUrl = preview.imageUrl ?: st.imageUrl,
                    url = preview.url,
                )
            }
        }
    }

    fun save(onDone: () -> Unit) {
        val st = _uiState.value
        if (!st.canSave) return
        val rawPrice = st.price.replace(",", ".").replace(" ", "").toDoubleOrNull()
        // Clamp цены: серверный validation reject'ит NaN/negative/over-limit с 400,
        // а SyncManager не различает validation-400 от transient → бесконечный retry.
        // Null (поле пустое) оставляем как есть — цена optional.
        val clampedPrice = rawPrice?.let {
            when {
                it.isNaN() || it.isInfinite() -> 0.0
                it < 0 -> 0.0
                it > FieldLimits.PRICE_MAX -> FieldLimits.PRICE_MAX
                else -> it
            }
        }
        val draft = WishDraft(
            title = st.title.trim(),
            description = st.description.ifBlank { null },
            url = st.url.ifBlank { null },
            imageUrl = st.imageUrl.ifBlank { null },
            price = clampedPrice,
            currency = st.currency.ifBlank { "RUB" },
            storeName = st.storeName.ifBlank { null },
        )
        viewModelScope.launch {
            val original = originalWish
            if (st.isEditMode && original != null) {
                repository.updateWish(original, draft)
            } else {
                repository.addWish(wishlistId, draft)
                // Guest-счётчик инкрементируем только при создании.
                guestCounter?.incrementWish()
            }
            // Push в application scope — не отменяется при popBackStack (#45)
            syncManager?.pushPendingScoped()
            onDone()
        }
    }

    private fun numberForField(value: Double): String {
        return if (value == value.toLong().toDouble()) {
            value.toLong().toString()
        } else {
            value.toString()
        }
    }

    private inline fun update(transform: AddWishUiState.() -> AddWishUiState) {
        _uiState.update(transform)
    }
}
