package com.nervs.wantly.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringPreferencesKey
import androidx.datastore.preferences.core.longPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore("wantly_session")

/**
 * Управляет сессией пользователя.
 * В гостевом режиме token=null — приложение работает только с локальной БД.
 * После регистрации/входа token сохраняется — приложение работает с сервером.
 */
class SessionManager(private val context: Context) {

    private object Keys {
        val TOKEN = stringPreferencesKey("jwt_token")
        val USER_ID = longPreferencesKey("user_id")
        val EMAIL = stringPreferencesKey("email")
        val DISPLAY_NAME = stringPreferencesKey("display_name")
    }

    val token: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }
    val isLoggedIn: Flow<Boolean> = token.map { it != null }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }
    val email: Flow<String?> = context.dataStore.data.map { it[Keys.EMAIL] }

    suspend fun saveSession(token: String, userId: Long, email: String, displayName: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
            prefs[Keys.USER_ID] = userId
            prefs[Keys.EMAIL] = email
            if (displayName != null) prefs[Keys.DISPLAY_NAME] = displayName
        }
    }

    suspend fun clearSession() {
        context.dataStore.edit { it.clear() }
    }

    /** Синхронное чтение токена для DI (OkHttp interceptor). */
    fun tokenBlocking(): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            context.dataStore.data.first()[Keys.TOKEN]
        }
    }.getOrNull()
}
