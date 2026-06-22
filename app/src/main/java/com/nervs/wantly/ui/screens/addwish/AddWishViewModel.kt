package com.nervs.wantly.ui.screens.addwish

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
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
) {
    val canSave: Boolean get() = title.isNotBlank()
}

class AddWishViewModel(
    private val wishlistId: Long,
    private val repository: WishlistRepository,
    private val guestCounter: com.nervs.wantly.data.GuestCounter? = null,
    private val sessionManager: com.nervs.wantly.data.SessionManager? = null,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AddWishUiState())
    val uiState = _uiState.asStateFlow()

    fun onUrlChange(v: String) = update { copy(url = v) }
    fun onTitleChange(v: String) = update { copy(title = v) }
    fun onDescriptionChange(v: String) = update { copy(description = v) }
    fun onPriceChange(v: String) = update {
        copy(price = v.filter { it.isDigit() || it == '.' || it == ',' || it == ' ' })
    }

    fun onCurrencyChange(v: String) = update { copy(currency = v) }
    fun onStoreChange(v: String) = update { copy(storeName = v) }
    fun onImageUrlChange(v: String) = update { copy(imageUrl = v) }

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
        viewModelScope.launch {
            repository.addWish(
                wishlistId,
                WishDraft(
                    title = st.title.trim(),
                    description = st.description.ifBlank { null },
                    url = st.url.ifBlank { null },
                    imageUrl = st.imageUrl.ifBlank { null },
                    price = st.price.replace(",", ".").replace(" ", "").toDoubleOrNull(),
                    currency = st.currency.ifBlank { "RUB" },
                    storeName = st.storeName.ifBlank { null },
                ),
            )
            guestCounter?.incrementWish()
            onDone()
        }
    }

    private inline fun update(transform: AddWishUiState.() -> AddWishUiState) {
        _uiState.update(transform)
    }

    private fun numberForField(v: Double): String =
        if (v % 1.0 == 0.0) "%.0f".format(v) else "%.2f".format(v)
}
