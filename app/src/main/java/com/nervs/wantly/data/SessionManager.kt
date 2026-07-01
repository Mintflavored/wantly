package com.nervs.wantly.data

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.booleanPreferencesKey
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
        /**
         * Email аккаунта, чьи dirty rows остались в Room после AUTH_EXPIRED logout.
         * При следующем login проверяется: если email не совпадает → Room вытирается,
         * иначе данные чужого аккаунта уйдут под новым токеном.
         * Null = нет pending данных (нормальный гостевой/первый вход).
         */
        val PENDING_RELOGIN_EMAIL = stringPreferencesKey("pending_relogin_email")
        val OWNER_BACKFILL_DONE = booleanPreferencesKey("owner_backfill_done")
    }

    val token: Flow<String?> = context.dataStore.data.map { it[Keys.TOKEN] }
    val isLoggedIn: Flow<Boolean> = token.map { it != null }
    val displayName: Flow<String?> = context.dataStore.data.map { it[Keys.DISPLAY_NAME] }
    val email: Flow<String?> = context.dataStore.data.map { it[Keys.EMAIL] }
    val pendingReloginEmail: Flow<String?> =
        context.dataStore.data.map { it[Keys.PENDING_RELOGIN_EMAIL] }

    suspend fun saveSession(token: String, userId: Long, email: String, displayName: String?) {
        context.dataStore.edit { prefs ->
            prefs[Keys.TOKEN] = token
            prefs[Keys.USER_ID] = userId
            prefs[Keys.EMAIL] = email
            if (displayName != null) prefs[Keys.DISPLAY_NAME] = displayName
        }
    }

    /** Запомнить email аккаунта, чьи dirty rows сохранены после AUTH_EXPIRED. */
    suspend fun setPendingReloginEmail(email: String?) {
        context.dataStore.edit { prefs ->
            if (email == null) prefs.remove(Keys.PENDING_RELOGIN_EMAIL)
            else prefs[Keys.PENDING_RELOGIN_EMAIL] = email
        }
    }

    /** True если backfill ownerEmail после миграции v3 уже выполнен. */
    suspend fun isOwnerBackfillDone(): Boolean =
        context.dataStore.data.first()[Keys.OWNER_BACKFILL_DONE] == true

    /** Пометить backfill выполненным. */
    suspend fun markOwnerBackfillDone() {
        context.dataStore.edit { it[Keys.OWNER_BACKFILL_DONE] = true }
    }

    suspend fun clearSession() {
        context.dataStore.edit { prefs ->
            prefs.remove(Keys.TOKEN)
            prefs.remove(Keys.USER_ID)
            prefs.remove(Keys.EMAIL)
            prefs.remove(Keys.DISPLAY_NAME)
            // PENDING_RELOGIN_EMAIL НЕ трогаем — он нужен для проверки
            // при следующем login (другой юзер → вытирать Room).
        }
    }

    /** Синхронное чтение токена для DI (OkHttp interceptor). */
    fun tokenBlocking(): String? = runCatching {
        kotlinx.coroutines.runBlocking {
            context.dataStore.data.first()[Keys.TOKEN]
        }
    }.getOrNull()

    /** Синхронная проверка залогинен ли пользователь (для миграции БД). */
    fun isLoggedInBlocking(): Boolean = tokenBlocking() != null
}
