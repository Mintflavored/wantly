package com.nervs.wantly.ui.screens.addwish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.R
import com.nervs.wantly.data.FieldLimits
import com.nervs.wantly.data.GuestCounter
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.model.WishDraft
import com.nervs.wantly.data.remote.LinkPreviewError
import com.nervs.wantly.data.repository.WishlistRepository
import com.nervs.wantly.ui.SnackbarController
import com.nervs.wantly.ui.SnackbarMessage
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
    /**
     * Кнопка Save активна только когда все обязательные поля валидны по серверным
     * правилам. Без этих проверок UI позволил бы сохранить ftp://-URL или `руб`-
     * валюту → бэкенд 400 → SyncManager бесконечно ретраит → блокирует logout.
     * Правила — зеркало backend validation/Validators.kt.
     */
    val canSave: Boolean
        get() = title.isNotBlank() &&
            isValidUrl(url) &&
            isValidUrl(imageUrl) &&
            // Нормализуем перед regex-match: старые wish'ы могли хранить lowercase
            // валюту (до PR #9), и prefill копирует её как есть. Backend тоже
            // normalizes (trim+uppercase) — canSave должен быть симметричен.
            currency.trim().uppercase().matches(CURRENCY_REGEX)

    /**
     * Пустой URL ок (поле optional). Непустой нормализуется как backend
     * (normalizeWishUrl): schemeless → https:// prefix, не-HTTP схемы (ftp/javascript)
     * reject'ятся. Так UI принимает `example.com` (как раньше), но не `ftp://`.
     */
    private fun isValidUrl(value: String): Boolean {
        val trimmed = value.trim()
        if (trimmed.isEmpty()) return true
        // Scheme detection — case-insensitive, покрываем оба варианта:
        //   - scheme:// (http://, https://, ftp://, ...)
        //   - scheme: без // (mailto:, javascript:, data:, ...)
        // Зеркало backend normalizeWishUrl — иначе javascript:alert(1) счёлся бы
        // schemeless, получил https:// prefix и прошёл canSave, а сервер reject'нул бы.
        val schemeMatch = Regex("^[a-zA-Z][a-zA-Z0-9+.-]*:(?://)?").find(trimmed)
        val normalized = when {
            schemeMatch == null -> "https://$trimmed"
            schemeMatch.value.lowercase() in setOf("http://", "https://") -> trimmed
            else -> return false // не-HTTP схема
        }
        val lower = normalized.lowercase()
        return lower.startsWith("http://") || lower.startsWith("https://")
    }

    private companion object {
        val CURRENCY_REGEX = Regex("^[A-Z]{3}$")
    }
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
    fun onCurrencyChange(v: String) = update {
        // Trim+uppercase ДО take(3): иначе вставка " usd" обрезается в " us" →
        // backend нормализует в "US" → 400 (длина 2) → бесконечный retry.
        copy(currency = v.trim().uppercase().take(3))
    }
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
                    // Clamps дублируют onValueChange: preview-сервис может вернуть
                    // поля длиннее серверных caps → без clamp save даст 400 и wish
                    // зависнет в retry.
                    title = preview.title?.let { FieldLimits.clamp(it, FieldLimits.WISH_TITLE_MAX) } ?: st.title,
                    description = preview.description?.let {
                        FieldLimits.clamp(it, FieldLimits.WISH_DESCRIPTION_MAX)
                    } ?: st.description,
                    price = preview.price?.let { numberForField(it) } ?: st.price,
                    currency = preview.currency?.trim()?.uppercase()?.take(3) ?: st.currency,
                    storeName = preview.storeName?.let {
                        FieldLimits.clamp(it, FieldLimits.WISH_STORE_MAX)
                    } ?: st.storeName,
                    imageUrl = preview.imageUrl?.let { FieldLimits.clamp(it, FieldLimits.URL_MAX) } ?: st.imageUrl,
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
                SnackbarController.send(SnackbarMessage(R.string.snackbar_wish_saved))
            } else {
                repository.addWish(wishlistId, draft)
                // Guest-счётчик инкрементируем только при создании.
                guestCounter?.incrementWish()
                SnackbarController.send(SnackbarMessage(R.string.snackbar_wish_added))
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
