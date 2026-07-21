package com.nervs.wantly.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nervs.wantly.R
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.WishlistDto
import com.nervs.wantly.data.repository.WishlistRepository
import com.nervs.wantly.test.FakeApi
import com.nervs.wantly.ui.screens.wishlist.WishlistDetailUiState
import com.nervs.wantly.ui.screens.wishlist.WishlistDetailViewModel
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.UnconfinedTestDispatcher
import kotlinx.coroutines.test.advanceUntilIdle
import kotlinx.coroutines.test.resetMain
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.test.setMain
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты WishlistDetailViewModel state machine + init-фикс.
 *
 * Проверяет регрессии аудита:
 *  - C2: Loading → skeleton (не ложный «Список не найден» на первом кадре).
 *  - NotFound: список действительно не существует → отдельная ветка.
 *  - PROBLEM 3 (init `.first { it != null }` зависал для удалённого списка):
 *    loadShareToken должен завершаться, а не висеть бесконечно.
 *  - M5: ошибка загрузки token выставляет tokenLoadError, а не оставляет
 *    isLoadingToken stuck true.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class WishlistDetailViewModelTest {

    private lateinit var db: WantlyDatabase
    private lateinit var repository: WishlistRepository
    private lateinit var syncManager: SyncManager
    private lateinit var fakeApi: FakeApi
    private lateinit var api: WantlyApi
    private val sessionManager: SessionManager = mockk(relaxed = true)

    @Before
    fun setup() = runTest {
        Dispatchers.setMain(UnconfinedTestDispatcher())
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WantlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeApi()
        api = fakeApi.mock()
        coEvery { sessionManager.email } returns flowOf(null)
        repository = WishlistRepository(db.wishlistDao(), db.wishDao(), mockk(relaxed = true), api, sessionManager)
        syncManager = SyncManager(db, api)
        com.nervs.wantly.ui.SnackbarController.clearForTest()
    }

    @After
    fun teardown() {
        db.close()
        Dispatchers.resetMain()
    }

    @Test
    fun `initial state is Loading before Room emits`() {
        val vm = WishlistDetailViewModel(wishlistId = 999L, repository, syncManager, api)
        assertThat(vm.state.value).isEqualTo(WishlistDetailUiState.Loading)
    }

    @Test
    fun `non-existent wishlist becomes NotFound`() = runTest {
        val vm = WishlistDetailViewModel(wishlistId = 999L, repository, syncManager, api)
        val state = vm.state.first { it !is WishlistDetailUiState.Loading }
        assertThat(state).isEqualTo(WishlistDetailUiState.NotFound)
    }

    @Test
    fun `existing wishlist becomes Loaded`() = runTest {
        val localId = db.wishlistDao().insert(WishlistEntity(title = "Birthday", synced = false))
        val vm = WishlistDetailViewModel(localId, repository, syncManager, api)
        val state = vm.state.first { it !is WishlistDetailUiState.Loading }
        assertThat(state).isInstanceOf(WishlistDetailUiState.Loaded::class.java)
        assertThat((state as WishlistDetailUiState.Loaded).wishlist.title).isEqualTo("Birthday")
    }

    @Test
    fun `loadShareToken completes (not hangs) for non-existent wishlist`() = runTest {
        // Регрессия PROBLEM 3: `.first { it != null }` подвисал для удалённого списка.
        // С фиксом `.first()` возвращает null мгновенно → isLoadingToken=false.
        val vm = WishlistDetailViewModel(wishlistId = 999L, repository, syncManager, api)
        // Ждём завершения init { loadShareToken() }. first{} пропускает initial=true.
        vm.isLoadingToken.first { !it }
        assertThat(vm.isLoadingToken.value).isFalse()
        assertThat(vm.tokenLoadError.value).isFalse()
        // shareToken остаётся null — мы не смогли его загрузить.
        assertThat(vm.shareToken.value).isNull()
    }

    @Test
    fun `loadShareToken sets tokenLoadError on API failure`() = runTest {
        // Существующий список с serverId, но API падает при getWishlistDetail.
        val localId = db.wishlistDao().insert(
            WishlistEntity(title = "Shared", synced = true, serverId = 42L),
        )
        // Override FakeApi: getWishlistDetail бросает сетевую ошибку.
        coEvery { api.getWishlistDetail(42L) } throws RuntimeException("network down")

        val vm = WishlistDetailViewModel(localId, repository, syncManager, api)
        vm.isLoadingToken.first { !it }

        // Регрессия M5: раньше isLoadingToken оставался true навсегда.
        assertThat(vm.isLoadingToken.value).isFalse()
        assertThat(vm.tokenLoadError.value).isTrue()
    }

    @Test
    fun `loadShareToken success populates shareToken`() = runTest {
        val localId = db.wishlistDao().insert(
            WishlistEntity(title = "Shared", synced = true, serverId = 42L),
        )
        fakeApi.seed(
            WishlistDto(id = 42L, title = "Shared", isShared = true, shareToken = "abc123"),
        )

        val vm = WishlistDetailViewModel(localId, repository, syncManager, api)
        vm.isLoadingToken.first { !it }

        assertThat(vm.isLoadingToken.value).isFalse()
        assertThat(vm.tokenLoadError.value).isFalse()
        assertThat(vm.shareToken.value).isEqualTo("abc123")
    }

    @Test
    fun `loadShareToken skip for local-only wishlist without serverId`() = runTest {
        val localId = db.wishlistDao().insert(
            WishlistEntity(title = "Guest", synced = false, serverId = null),
        )

        val vm = WishlistDetailViewModel(localId, repository, syncManager, api)
        vm.isLoadingToken.first { !it }

        assertThat(vm.isLoadingToken.value).isFalse()
        assertThat(vm.tokenLoadError.value).isFalse()
        // API не должен был вызываться — нет serverId.
        assertThat(vm.shareToken.value).isNull()
    }

    // ── Undo delete wish ─────────────────────────────────────────────

    @Test
    fun `deleteWish soft-deletes and restoreWish brings it back`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "List", synced = false))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Toy", synced = false),
        )

        val vm = WishlistDetailViewModel(listId, repository, syncManager, api)
        vm.deleteWish(WishEntity(id = wishId, wishlistId = listId, title = "Toy", synced = false))
        advanceUntilIdle()

        // Soft-deleted: getPendingDelete пуст пока undo-окно открыто (undoProtected=1).
        assertThat(db.wishDao().getPendingDelete()).isEmpty()

        // Undo.
        repository.restoreWish(wishId)
        advanceUntilIdle()

        assertThat(db.wishDao().getById(wishId)).isNotNull()
        assertThat(db.wishDao().getPendingDelete()).isEmpty()
    }

    @Test
    fun `restoreWish preserves synced state for server-backed rows`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "List", synced = true, serverId = 1L))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Toy", synced = true, serverId = 10L),
        )

        val vm = WishlistDetailViewModel(listId, repository, syncManager, api)
        vm.deleteWish(WishEntity(id = wishId, wishlistId = listId, title = "Toy", synced = true, serverId = 10L))
        advanceUntilIdle()

        // Undo: synced должен вернуться в 1 (данные не изменились).
        repository.restoreWish(wishId)
        advanceUntilIdle()

        val row = db.wishDao().getById(wishId)
        assertThat(row).isNotNull()
        assertThat(row!!.pendingDelete).isFalse()
        assertThat(row.synced).isTrue()
    }

    @Test
    fun `deleteWish sends undo Snackbar message`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "List", synced = false))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Toy", synced = false),
        )

        val vm = WishlistDetailViewModel(listId, repository, syncManager, api)

        // SharedFlow(replay=0): подписчик должен быть ДО send.
        val deferred = kotlinx.coroutines.CompletableDeferred<com.nervs.wantly.ui.SnackbarMessage>()
        val collectJob = launch {
            com.nervs.wantly.ui.SnackbarController.events.collect { deferred.complete(it) }
        }
        advanceUntilIdle()

        vm.deleteWish(WishEntity(id = wishId, wishlistId = listId, title = "Toy", synced = false))
        advanceUntilIdle()

        val msg = deferred.await()
        assertThat(msg.actionLabelRes).isEqualTo(R.string.snackbar_action_undo)
        collectJob.cancel()
    }
}
