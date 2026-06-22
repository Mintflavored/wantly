package com.nervs.wantly.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.remote.ApiException
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch

data class AuthUiState(
    val email: String = "",
    val password: String = "",
    val displayName: String = "",
    val isLoading: Boolean = false,
    val error: String? = null,
    val isSuccess: Boolean = false,
)

class AuthViewModel(
    private val sessionManager: SessionManager,
    private val repository: WishlistRepository,
    private val syncManager: com.nervs.wantly.data.SyncManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    fun onEmailChange(v: String) = update { copy(email = v) }
    fun onPasswordChange(v: String) = update { copy(password = v) }
    fun onNameChange(v: String) = update { copy(displayName = v) }

    fun register() {
        val st = _uiState.value
        if (st.email.isBlank() || st.password.isBlank()) return
        update { copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                // Блокируем авто-синхронизацию ДО saveSession — иначе
                // LaunchedEffect(isLoggedIn) успеет запустить fullSync
                // и стереть Room до завершения миграции.
                syncManager.skipAutoSync = true
                val resp = sessionManager.run {
                    val r = repository.api.register(st.email.trim(), st.password, st.displayName.ifBlank { null })
                    saveSession(r.token, r.userId, r.email, r.displayName)
                    r
                }
                // Мигрируем локальные данные гостя на сервер.
                val migrated = runCatching { repository.migrateLocalToServer() }.isSuccess
                if (migrated) {
                    // fullSync — критичен: заменяет локальные гостевые ID
                    // на серверные. Не глушим ошибку — если упало, пользователь
                    // увидит сообщение и сможет повторить вход.
                    syncManager.fullSync()
                }
                update { copy(isLoading = false, isSuccess = true) }
            } catch (e: ApiException) {
                update { copy(isLoading = false, error = e.message ?: "Ошибка регистрации") }
            } catch (e: Exception) {
                update { copy(isLoading = false, error = "Проверьте интернет-соединение") }
            } finally {
                // Гарантированный сброс флага — даже при ошибке регистрации
                syncManager.skipAutoSync = false
            }
        }
    }

    fun login() {
        val st = _uiState.value
        if (st.email.isBlank() || st.password.isBlank()) return
        update { copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val resp = sessionManager.run {
                    val r = repository.api.login(st.email.trim(), st.password)
                    saveSession(r.token, r.userId, r.email, r.displayName)
                    r
                }
                runCatching { syncManager.fullSync() }
                update { copy(isLoading = false, isSuccess = true) }
            } catch (e: ApiException) {
                update { copy(isLoading = false, error = e.message ?: "Ошибка входа") }
            } catch (e: Exception) {
                update { copy(isLoading = false, error = "Проверьте интернет-соединение") }
            }
        }
    }

    private inline fun update(transform: AuthUiState.() -> AuthUiState) {
        _uiState.update(transform)
    }
}
