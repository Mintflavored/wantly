package com.nervs.wantly.ui

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
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
 * SnackbarHost (WantlyNavHost). MutableSharedFlow с replay=8 — до 8 событий
 * сохраняются для нового подписчика (Activity recreation, LaunchedEffect ещё
 * не активен). extraBufferCapacity=UNLIMITED — события не теряются при burst.
 *
 * replay=8 покрывает реалистичный сценарий: user удалил несколько items перед
 * Activity recreation. Каждое undo-событие должно дойти до collector'а, иначе
 * onDismiss не выполнится и undoProtected tombstone останется скрытым навсегда.
 */
object SnackbarController {
    private val _events = MutableSharedFlow<SnackbarMessage>(
        replay = 8,
        extraBufferCapacity = Int.MAX_VALUE,
    )
    val events: SharedFlow<SnackbarMessage> = _events.asSharedFlow()

    fun send(message: SnackbarMessage) {
        _events.tryEmit(message)
    }

    /**
     * Reference на активный SnackbarHostState — устанавливается из WantlyNavHost.
     * Используется [dismissActive] для принудительного закрытия Snackbar (logout).
     */
    @Volatile
    private var activeHost: SnackbarHostState? = null

    fun bindHost(host: SnackbarHostState) {
        activeHost = host
    }

    /**
     * Принудительно закрывает активный Snackbar (если есть) и очищает replay.
     * Вызывается при logout — pushPendingVerifiedForLogout коммитит tombstones,
     * но Snackbar с Undo остаётся видимым → user может нажать Undo на уже
     * удалённой записи.
     */
    fun dismissActive() {
        activeHost?.let { host ->
            host.currentSnackbarData?.dismiss()
        }
        clearHandled()
    }

    /** Очищает replay cache — вызывается после обработки Snackbar, чтобы
     *  Activity recreation не получил уже показанный Snackbar снова. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearHandled() {
        _events.resetReplayCache()
    }

    /** Очищает буфер (для тестов). */
    fun clearForTest() = clearHandled()
}
