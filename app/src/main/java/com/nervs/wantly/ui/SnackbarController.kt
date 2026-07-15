package com.nervs.wantly.ui

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow

/**
 * Одно сообщение для Snackbar — эмитится из ViewModel, обрабатывается в UI.
 *
 * @param messageRes текст сообщения (локализованный string resource).
 * @param actionLabelRes текст action-кнопки (null → без кнопки).
 * @param onAction выполняется в UI scope если пользователь нажал action.
 *        Для undo: restoreDeleted.
 * @param onDismiss выполняется если Snackbar закрылся по таймауту или swipe
 *        (НЕ по action). Для undo-delete: запускает отложенный push.
 * @param duration длительность показа.
 */
data class SnackbarMessage(
    @StringRes val messageRes: Int,
    @StringRes val actionLabelRes: Int? = null,
    val onAction: (suspend () -> Unit)? = null,
    val onDismiss: (suspend () -> Unit)? = null,
    val duration: SnackbarDuration = SnackbarDuration.Short,
)

/**
 * Singleton event-bus для передачи Snackbar-событий из любой ViewModel в общий
 * SnackbarHost (WantlyNavHost). MutableSharedFlow с replay=0 — события не
 * кэшируются для новых подписчиков, показываются последовательно.
 */
/**
 * Singleton event-bus для передачи Snackbar-событий из любой ViewModel в общий
 * SnackbarHost (WantlyNavHost). MutableSharedFlow с replay=0 и UNLIMITED buffer —
 * события не кэшируются для новых подписчиков, но никогда не теряются при
 * переполнении (иначе undo-delete onDismiss никогда бы не вызвался →
 * undoProtected tombstone остался бы скрытым от sync навсегда).
 */
object SnackbarController {
    private val _events = MutableSharedFlow<SnackbarMessage>(
        replay = 0,
        extraBufferCapacity = Int.MAX_VALUE, // UNLIMITED — не терять undo-события
    )
    val events: SharedFlow<SnackbarMessage> = _events.asSharedFlow()

    fun send(message: SnackbarMessage) {
        _events.tryEmit(message)
    }

    /** Очищает буфер (для тестов). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearForTest() {
        _events.resetReplayCache()
    }
}
