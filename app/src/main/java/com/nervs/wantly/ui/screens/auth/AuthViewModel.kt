package com.nervs.wantly.ui.screens.auth

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.nervs.wantly.data.FieldLimits
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.remote.ApiException
import com.nervs.wantly.data.repository.WishlistRepository
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
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
    private val syncManager: SyncManager,
) : ViewModel() {
    private val _uiState = MutableStateFlow(AuthUiState())
    val uiState = _uiState.asStateFlow()

    fun onEmailChange(v: String) = update { copy(email = FieldLimits.clamp(v, FieldLimits.EMAIL_MAX)) }
    fun onPasswordChange(v: String) = update { copy(password = v.take(FieldLimits.PASSWORD_MAX)) }
    fun onNameChange(v: String) = update { copy(displayName = FieldLimits.clamp(v, FieldLimits.DISPLAY_NAME_MAX)) }

    /**
     * Перед login/register: убедиться что Room не содержит данных чужого аккаунта.
     *
     * Два источника «чужих» rows:
     * 1. ownerEmail на самой row отличается от [newEmail] (новый механизм).
     * 2. row создана до schema-migration 2→3 (ownerEmail=NULL), но была
     *    оставлена при AUTH_EXPIRED logout — фиксируется через
     *    SessionManager.pendingReloginEmail в ProfileScreen.
     *
     * Возвращает true если Room был вычищен.
     */
    private suspend fun guardAgainstForeignRows(newEmail: String): Boolean {
        // (2) — pending relogin email
        val pendingEmail = sessionManager.pendingReloginEmail.first()
        if (pendingEmail != null && pendingEmail != newEmail) {
            syncManager.clearLocal()
            sessionManager.setPendingReloginEmail(null)
            return true
        }
        sessionManager.setPendingReloginEmail(null)
        // (1) — ownerEmail на rows
        return syncManager.clearLocalIfOwnedByOther(newEmail)
    }

    fun register() {
        val st = _uiState.value
        if (st.email.isBlank() || st.password.isBlank()) return
        update { copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = repository.api.register(st.email.trim(), st.password, st.displayName.ifBlank { null })
                guardAgainstForeignRows(r.email)
                // Привязываем все guest-rows (включая legacy без ownerEmail,
                // если guard их не вытер) к этому аккаунту.
                syncManager.claimGuestRows(r.email)
                sessionManager.saveSession(r.token, r.userId, r.email, r.displayName)
                update { copy(isLoading = false, isSuccess = true) }
                syncManager.syncAfterAuthScoped(isRegistration = true)
            } catch (e: ApiException) {
                update { copy(isLoading = false, error = e.message ?: "Ошибка регистрации") }
            } catch (e: Exception) {
                update { copy(isLoading = false, error = "Проверьте интернет-соединение") }
            }
        }
    }

    fun login() {
        val st = _uiState.value
        if (st.email.isBlank() || st.password.isBlank()) return
        update { copy(isLoading = true, error = null) }
        viewModelScope.launch {
            try {
                val r = repository.api.login(st.email.trim(), st.password)
                guardAgainstForeignRows(r.email)
                syncManager.claimGuestRows(r.email)
                sessionManager.saveSession(r.token, r.userId, r.email, r.displayName)
                update { copy(isLoading = false, isSuccess = true) }
                syncManager.syncAfterAuthScoped(isRegistration = false)
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
