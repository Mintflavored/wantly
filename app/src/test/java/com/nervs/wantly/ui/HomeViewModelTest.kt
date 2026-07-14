package com.nervs.wantly.ui

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nervs.wantly.R
import com.nervs.wantly.data.SessionManager
import com.nervs.wantly.data.SyncManager
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.repository.WishlistRepository
import com.nervs.wantly.test.FakeApi
import com.nervs.wantly.ui.screens.home.HomeUiState
import com.nervs.wantly.ui.screens.home.HomeViewModel
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
 * Тесты HomeViewModel state machine: Loading → Loaded/Error.
 *
 * Проверяет регрессии аудита:
 *  - M4: первый рендер не должен показывать ложное empty state,
 *    пока Room не отдал первую эмиссию.
 *  - Loading → skeleton, Loaded.empty → EmptyHome, Loaded.list → список.
 */
@OptIn(ExperimentalCoroutinesApi::class)
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class HomeViewModelTest {

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
        // Гостевой режим: email=null → видны все списки (ownerEmail IS NULL).
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
        // stateIn initial value — доступно сразу без подписки.
        val vm = HomeViewModel(repository, syncManager)
        assertThat(vm.state.value).isEqualTo(HomeUiState.Loading)
    }

    @Test
    fun `empty Room becomes Loaded with empty list`() = runTest {
        val vm = HomeViewModel(repository, syncManager)
        val state = vm.state.first { it !is HomeUiState.Loading }
        assertThat(state).isInstanceOf(HomeUiState.Loaded::class.java)
        assertThat((state as HomeUiState.Loaded).wishlists).isEmpty()
    }

    @Test
    fun `wishlists in Room become Loaded with list`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "Birthday", synced = false))
        db.wishlistDao().insert(WishlistEntity(title = "Wedding", synced = false))

        val vm = HomeViewModel(repository, syncManager)
        val state = vm.state.first { it !is HomeUiState.Loading }
        assertThat(state).isInstanceOf(HomeUiState.Loaded::class.java)
        assertThat((state as HomeUiState.Loaded).wishlists).hasSize(2)
    }

    // ── Undo delete ──────────────────────────────────────────────────

    @Test
    fun `deleteWishlist marks row as pendingDelete (soft delete)`() = runTest {
        val id = db.wishlistDao().insert(WishlistEntity(title = "Birthday", synced = false))

        val vm = HomeViewModel(repository, syncManager)
        vm.deleteWishlist(WishlistEntity(id = id, title = "Birthday", synced = false))
        advanceUntilIdle()

        // Row не удалён физически — markDeleted поставил pendingDelete=1.
        val row = db.wishlistDao().getById(id)
        assertThat(row).isNull() // observeById фильтрует pendingDelete=0 → null в UI-запросе
        // Но getPendingDelete находит tombstone:
        assertThat(db.wishlistDao().getPendingDelete()).hasSize(1)
    }

    @Test
    fun `restoreWishlist brings row back from soft delete`() = runTest {
        val id = db.wishlistDao().insert(WishlistEntity(title = "Birthday", synced = false))

        val vm = HomeViewModel(repository, syncManager)
        vm.deleteWishlist(WishlistEntity(id = id, title = "Birthday", synced = false))
        advanceUntilIdle()

        // Undo: restore через repository.
        repository.restoreWishlist(id)
        advanceUntilIdle()

        val row = db.wishlistDao().getById(id)
        assertThat(row).isNotNull()
        assertThat(row!!.title).isEqualTo("Birthday")
        assertThat(row.pendingDelete).isFalse()
        assertThat(db.wishlistDao().getPendingDelete()).isEmpty()
    }

    @Test
    fun `deleteWishlist sends undo Snackbar message`() = runTest {
        val id = db.wishlistDao().insert(WishlistEntity(title = "Birthday", synced = false))
        val vm = HomeViewModel(repository, syncManager)

        // SharedFlow(replay=0): подписчик должен быть ДО send.
        val deferred = kotlinx.coroutines.CompletableDeferred<com.nervs.wantly.ui.SnackbarMessage>()
        val collectJob = launch {
            com.nervs.wantly.ui.SnackbarController.events.collect { deferred.complete(it) }
        }
        advanceUntilIdle() // даём подписке активироваться

        vm.deleteWishlist(WishlistEntity(id = id, title = "Birthday", synced = false))
        advanceUntilIdle()

        val msg = deferred.await()
        assertThat(msg.actionLabelRes).isEqualTo(R.string.snackbar_action_undo)
        collectJob.cancel()
    }

    // ── Unsynced count ───────────────────────────────────────────────

    @Test
    fun `observeUnsyncedCount counts dirty wishlists`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "Synced", synced = true, ownerEmail = "a@b.c"))
        db.wishlistDao().insert(WishlistEntity(title = "Dirty", synced = false, ownerEmail = "a@b.c"))

        val count = repository.observeUnsyncedCount().first()
        assertThat(count).isEqualTo(1)
    }

    @Test
    fun `observeUnsyncedCount ignores guest rows without ownerEmail`() = runTest {
        // Guest rows (ownerEmail = null) не считаются — они не пойдут на сервер.
        db.wishlistDao().insert(WishlistEntity(title = "Guest", synced = false, ownerEmail = null))

        val count = repository.observeUnsyncedCount().first()
        assertThat(count).isEqualTo(0)
    }

    @Test
    fun `observeUnsyncedCount includes pending-delete tombstones`() = runTest {
        // Tombstone: synced=0, pendingDelete=1 — delete push ещё не дошёл.
        // Сервер всё ещё содержит удалённый элемент → это несинхронизированное состояние.
        db.wishlistDao().insert(
            WishlistEntity(
                title = "Deleted offline",
                synced = false,
                pendingDelete = true,
                ownerEmail = "a@b.c",
            ),
        )

        val count = repository.observeUnsyncedCount().first()
        assertThat(count).isEqualTo(1)
    }
}
