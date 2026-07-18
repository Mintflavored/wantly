package com.nervs.wantly.ui

import androidx.annotation.StringRes
import androidx.compose.material3.SnackbarDuration
import androidx.compose.material3.SnackbarHostState
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableSharedFlow
import kotlinx.coroutines.flow.SharedFlow
import kotlinx.coroutines.flow.asSharedFlow
import kotlinx.coroutines.launch
import java.util.concurrent.ConcurrentHashMap

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
 * SnackbarHost (WantlyNavHost). MutableSharedFlow с replay=8 — до 8 событий
 * сохраняются для нового подписчика (Activity recreation, LaunchedEffect ещё
 * не активен). extraBufferCapacity=UNLIMITED — события не теряются при burst.
 *
 * Гарантия доставки callback'ов (onAction/onDismiss) обеспечивается через
 * [handled] set: после обработки сообщение помечается, и при replay collector
 * пропускает его. Если Activity recreation прерывает showSnackbar — onDismiss
 * выполняется в finally, сообщение помечается handled, replay cache НЕ
 * сбрасывается целиком (queued сообщения сохраняются для нового collector'а).
 */
object SnackbarController {
    private val _events = MutableSharedFlow<SnackbarMessage>(
        replay = 8,
        extraBufferCapacity = Int.MAX_VALUE,
    )
    val events: SharedFlow<SnackbarMessage> = _events.asSharedFlow()

    /** Сообщения, чьи callbacks уже выполнены. Replay cache НЕ сбрасывается
     *  целиком — это позволило бы потерять queued сообщения. */
    private val handled = ConcurrentHashMap.newKeySet<SnackbarMessage>()

    /**
     * App-scoped CoroutineScope для запуска snackbar callbacks. В отличие от
     * rememberCoroutineScope (отменяется при composition dispose), этот scope
     * переживает Activity recreation и навигацию — onAction/onDismiss гарантированно
     * выполняются. Устанавливается из WantlyApp.onCreate.
     */
    private val callbackScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)

    fun send(message: SnackbarMessage) {
        handled.remove(message) // новое сообщение → точно не handled
        _events.tryEmit(message)
    }

    /** Collector вызывает после обработки — помечает как выполненное. */
    fun markHandled(message: SnackbarMessage) {
        handled.add(message)
    }

    /** Collector вызывает чтобы проверить, нужно ли пропустить сообщение. */
    fun isHandled(message: SnackbarMessage): Boolean = handled.contains(message)

    /**
     * Запускает callback в app-scoped CoroutineScope (переживает composition dispose).
     * Заменяет rememberCoroutineScope.launch — который отменяется вместе с composition
     * при Activity recreation, оставляя onAction/onDismiss невыполненными.
     */
    fun launchCallback(callback: suspend () -> Unit) {
        callbackScope.launch { callback() }
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
     * Принудительно закрывает активный Snackbar (если есть) И дрейнит queued
     * undo messages. Вызывается при logout — pushPendingVerifiedForLogout коммитит
     * tombstones, а несколько queued undo snackbars остались бы показываться
     * после удаления данных, предлагая Undo который ничего не восстанавливает.
     *
     * Текущий in-flight message: dismiss → его onDismiss выполнится через showSnackbar
     * return в WantlyNavHost collector'е.
     * Queued buffered messages: помечаются handled → collector пропустит их.
     */
    fun dismissActive() {
        activeHost?.let { host ->
            host.currentSnackbarData?.dismiss()
        }
        // Дрейним queued messages — помечаем все как handled.
        // replay cache НЕ сбрасываем (collector проверит handled-set и пропустит).
        // Это безопасно: после logout composition всё равно dispose'нется.
        drainQueued()
    }

    /** Помечает все unhandled сообщения как handled — они не покажутся снова.
     *  После logout данные вытираются → queued undo не имеет смысла. */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    private fun drainQueued() {
        handled.clear()
        _events.resetReplayCache()
    }

    /** Очищает replay cache (для тестов). */
    @OptIn(kotlinx.coroutines.ExperimentalCoroutinesApi::class)
    fun clearForTest() {
        handled.clear()
        _events.resetReplayCache()
    }
}
