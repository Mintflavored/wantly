package com.nervs.wantly.data.model

import androidx.annotation.StringRes
import com.nervs.wantly.R

/**
 * Статусы желания. Локально (Фаза 1) — справочник.
 * В Фазе 4 статусы RESERVED/PURCHASED будут скрываться от владельца списка
 * (фича «сюрприз») серверными правилами доступа.
 *
 * Локализованный текст берётся из ресурса [labelRes] через stringResource() в UI.
 */
enum class WishStatus(@StringRes val labelRes: Int) {
    WANTED(R.string.status_wanted),
    RESERVED(R.string.status_reserved),
    PURCHASED(R.string.status_purchased);

    companion object {
        fun fromName(name: String?): WishStatus =
            entries.firstOrNull { it.name == name } ?: WANTED

        /** Цикл перехода по тапу: Хочу → Забронировано → Куплено → Хочу. */
        fun next(current: WishStatus): WishStatus = when (current) {
            WANTED -> RESERVED
            RESERVED -> PURCHASED
            PURCHASED -> WANTED
        }
    }
}
