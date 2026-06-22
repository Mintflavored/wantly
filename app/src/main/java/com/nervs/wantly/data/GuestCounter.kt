package com.nervs.wantly.data

import android.content.Context
import android.content.SharedPreferences

/**
 * Простой счётчик добавленных желаний для гостевого prompt'а.
 * Используем SharedPreferences вместо DataStore — это просто целое число.
 */
class GuestCounter(context: Context) {
    private val prefs: SharedPreferences =
        context.getSharedPreferences("wantly_guest", Context.MODE_PRIVATE)

    /** Сколько желаний гость уже добавил. */
    fun getWishCount(): Int = prefs.getInt(KEY_WISH_COUNT, 0)

    /** Увеличить счётчик (вызывается при добавлении желания в гостевом режиме). */
    fun incrementWish() = prefs.edit().putInt(KEY_WISH_COUNT, getWishCount() + 1).apply()

    /** Показывали ли уже prompt о регистрации. */
    fun wasPromptShown(): Boolean = prefs.getBoolean(KEY_PROMPT_SHOWN, false)

    fun markPromptShown() = prefs.edit().putBoolean(KEY_PROMPT_SHOWN, true).apply()

    /** Сброс при регистрации/входе. */
    fun reset() = prefs.edit().clear().apply()

    companion object {
        private const val KEY_WISH_COUNT = "wish_count"
        private const val KEY_PROMPT_SHOWN = "prompt_shown"
        /** После скольких желаний показываем prompt. */
        const val PROMPT_THRESHOLD = 3
    }
}
