package com.nervs.wantly.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity
import com.nervs.wantly.data.remote.WantlyApi
import com.nervs.wantly.data.remote.dto.WishlistDto
import com.nervs.wantly.test.FakeApi
import io.mockk.coEvery
import io.mockk.mockk
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Тесты SyncManager на in-memory Room (реальный SQLite) + FakeApi.
 *
 * Каждый тест воспроизводит конкретный баг из PR review, проверяет
 * что текущий фикс корректен и регрессия не вернётся.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class SyncManagerTest {

    private lateinit var db: WantlyDatabase
    private lateinit var fakeApi: FakeApi
    private lateinit var api: WantlyApi
    private lateinit var sync: SyncManager

    @Before
    fun setup() = runTest {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WantlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        fakeApi = FakeApi()
        api = fakeApi.mock()
        sync = SyncManager(db, api)
    }

    @After
    fun teardown() {
        db.close()
    }

    // ── pushPending: создание новых записей ──────────────────────────

    @Test
    fun `pushPending POSTs new wishlist and assigns serverId`() = runTest {
        val localId = db.wishlistDao().insert(
            WishlistEntity(title = "Birthday", synced = false),
        )

        sync.pushPending()

        val saved = db.wishlistDao().getById(localId)
        assertThat(saved).isNotNull()
        assertThat(saved!!.serverId).isNotNull()
        assertThat(saved.synced).isTrue()
        assertThat(fakeApi.wishlistIds()).hasSize(1)
        assertThat(fakeApi.wishlist(saved.serverId!!)!!.title).isEqualTo("Birthday")
    }

    @Test
    fun `pushPending POSTs new wish under already-synced wishlist`() = runTest {
        val listLocalId = db.wishlistDao().insert(
            WishlistEntity(title = "List", synced = false),
        )
        sync.pushPending() // создаёт wishlist на сервере

        val wishLocalId = db.wishDao().insert(
            WishEntity(wishlistId = listLocalId, title = "Toy", synced = false),
        )

        sync.pushPending()

        val saved = db.wishDao().getById(wishLocalId)
        assertThat(saved).isNotNull()
        assertThat(saved!!.serverId).isNotNull()
        assertThat(saved.synced).isTrue()
        assertThat(fakeApi.wishIds()).hasSize(1)
    }

    // ── pushPendingVerifiedForLogout: различение исходов logout ──────
    // (баг #20 — expired JWT блокировал logout)

    @Test
    fun `logout flush returns SUCCESS when all rows synced`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "L", synced = false))

        val outcome = sync.pushPendingVerifiedForLogout()

        assertThat(outcome).isEqualTo(LogoutSyncOutcome.SUCCESS)
        assertThat(db.wishlistDao().getUnsynced()).isEmpty()
    }

    @Test
    fun `logout flush returns TRANSIENT_FAILURE on network error`() = runTest {
        val brokenApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { createWishlist(any()) } throws RuntimeException("network down")
        }
        val brokenSync = SyncManager(db, brokenApi)

        db.wishlistDao().insert(WishlistEntity(title = "L", synced = false))

        val outcome = brokenSync.pushPendingVerifiedForLogout()

        assertThat(outcome).isEqualTo(LogoutSyncOutcome.TRANSIENT_FAILURE)
        assertThat(db.wishlistDao().getUnsynced()).hasSize(1)
    }

    @Test
    fun `logout flush returns AUTH_EXPIRED on 401`() = runTest {
        val expiredApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { createWishlist(any()) } throws com.nervs.wantly.data.remote.ApiException(401, "expired")
        }
        val expiredSync = SyncManager(db, expiredApi)

        db.wishlistDao().insert(WishlistEntity(title = "L", synced = false))

        val outcome = expiredSync.pushPendingVerifiedForLogout()

        // 401 = JWT истёк — Room НЕ вытирать, данные уйдут после re-login
        assertThat(outcome).isEqualTo(LogoutSyncOutcome.AUTH_EXPIRED)
        assertThat(db.wishlistDao().getUnsynced()).hasSize(1)
    }

    @Test
    fun `pushPending PATCHes existing wish status to server`() = runTest {
        // Setup: synced wishlist + synced wish, потом юзер меняет статус → dirty
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "W", serverId = 500, synced = true),
        )
        // Сервер знает эти записи
        fakeApi.seed(
            WishlistDto(id = 100, title = "L"),
            listOf(
                com.nervs.wantly.data.remote.dto.WishDto(
                    id = 500, wishlistId = 100, title = "W", status = "WANTED",
                ),
            ),
        )

        // Юзер меняет статус → dirty
        db.wishDao().updateStatus(10, "PURCHASED")

        sync.pushPending()

        // Сервер получил PATCH
        assertThat(fakeApi.wish(500)!!.status).isEqualTo("PURCHASED")
        // Локальная запись снова synced
        assertThat(db.wishDao().getById(10)!!.synced).isTrue()
    }

    @Test
    fun `pushPending sends tombstone DELETE to server when row exists remotely`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "W",
                serverId = 500,
                synced = false,
                pendingDelete = true,
            ),
        )
        fakeApi.seed(
            WishlistDto(id = 100, title = "L"),
            listOf(
                com.nervs.wantly.data.remote.dto.WishDto(
                    id = 500, wishlistId = 100, title = "W",
                ),
            ),
        )

        sync.pushPending()

        // Сервер удалил
        assertThat(fakeApi.wishIds()).doesNotContain(500L)
        // Локальная запись удалена (tombstone применён)
        assertThat(db.wishDao().getById(10)).isNull()
    }

    // ── pushPending: 404 на DELETE трактуется как success ────────────
    // (баг #12)
    @Test
    fun `pushPending DELETE tombstone returns 404 still clears local row`() = runTest {
        // Сначала создаём валидный wishlist, чтобы FK не нарушался
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 1, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "ghost",
                serverId = 999L, // нет на сервере
                synced = false,
                pendingDelete = true,
            ),
        )

        sync.pushPending()

        // 404 проглочен, локальная запись удалена — иначе зависла бы forever
        assertThat(db.wishDao().getById(10)).isNull()
    }

    @Test
    fun `pushPending PATCH 404 detaches serverId for recreation`() = runTest {
        // Wish с serverId, которого нет на сервере (другой клиент удалил).
        // PATCH должен вернуть 404 → wish отсоединяется для POST в следующий push.
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "W",
                status = "PURCHASED",
                serverId = 500,
                synced = false, // dirty → push попытается PATCH
            ),
        )
        // Сервер знает wishlist 100, но не знает wish 500
        fakeApi.seed(WishlistDto(id = 100, title = "L"))

        sync.pushPending()

        val saved = db.wishDao().getById(10)
        assertThat(saved).isNotNull()
        // serverId сброшен → следующий push POSTит под текущим parent
        assertThat(saved!!.serverId).isNull()
        assertThat(saved.synced).isFalse()
    }

    @Test
    fun `pushPending createWish 404 detaches parent serverId for recreate`() = runTest {
        // Локальный wish под списком, который удалён на сервере.
        // POST wish вернёт 404 (parent не найден) → parent serverId
        // отсоединяется для recreate-через-pull.
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "W",
                synced = false,
            ),
        )
        // Сервер не знает wishlist 100 (удалён другим клиентом).
        // FakeApi.createWish проверяет parent и кидает 404.

        sync.pushPending()

        // Parent отсоединён для recreate
        val parent = db.wishlistDao().getById(1)
        assertThat(parent).isNotNull()
        assertThat(parent!!.serverId).isNull()
        assertThat(parent.synced).isFalse()
    }

    @Test
    fun `pushPending createWish 404 detaches CLEAN siblings too`() = runTest {
        // Parent + dirty wish (триггерит createWish, который упадёт 404) +
        // clean sibling (не должен потеряться при parent-recreate).
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "dirty",
                synced = false, // триггерит createWish → 404
            ),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 11,
                wishlistId = 1,
                title = "clean",
                serverId = 501,
                synced = true, // clean sibling
            ),
        )

        sync.pushPending()

        // Parent отсоединён
        val parent = db.wishlistDao().getById(1)
        assertThat(parent!!.serverId).isNull()
        assertThat(parent.synced).isFalse()

        // Clean sibling тоже отсоединён и помечен dirty — попадёт в getUnsynced
        val cleanSibling = db.wishDao().getById(11)
        assertThat(cleanSibling).isNotNull()
        assertThat(cleanSibling!!.serverId).isNull()
        assertThat(cleanSibling.synced).isFalse()
    }

    // ── pullInternal UPSERT сохраняет локальный PK ───────────────────
    // (баг #7)

    @Test
    fun `pull UPSERT keeps local PK when server row exists`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 5, title = "Old title", serverId = 42, synced = true),
        )
        fakeApi.seed(WishlistDto(id = 42, title = "New title"))

        sync.pullInternal()

        val all = db.wishlistDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].id).isEqualTo(5L) // PK не изменился
        assertThat(all[0].serverId).isEqualTo(42L)
        assertThat(all[0].title).isEqualTo("New title")
        assertThat(all[0].synced).isTrue()
    }

    @Test
    fun `pull UPSERT inserts new local row when server row is new`() = runTest {
        fakeApi.seed(WishlistDto(id = 100, title = "Fresh"))

        sync.pullInternal()

        val all = db.wishlistDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].title).isEqualTo("Fresh")
        assertThat(all[0].serverId).isEqualTo(100L)
        assertThat(all[0].synced).isTrue()
    }

    // ── pullInternal prunes серверные удаления ───────────────────────

    @Test
    fun `pull deletes synced local wishlist absent on server`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 50, synced = true),
        )

        sync.pullInternal()

        assertThat(db.wishlistDao().getAll()).isEmpty()
    }

    @Test
    fun `pull preserves tombstone even when server dropped it`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(
                id = 1,
                title = "L",
                serverId = 50,
                synced = false,
                pendingDelete = true,
            ),
        )

        sync.pullInternal()

        val all = db.wishlistDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].pendingDelete).isTrue()
    }

    @Test
    fun `pull preserves dirty wishlist even when server dropped it`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(
                id = 1,
                title = "L",
                serverId = 50,
                synced = false,
            ),
        )

        sync.pullInternal()

        val all = db.wishlistDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].synced).isFalse()
    }

    // ── pullInternal parent-recreate conflict ────────────────────────
    // (баги #16, #18)

    @Test
    fun `pull recreates parent and resets child serverIds when parent deleted remotely`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 50, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "W",
                status = "PURCHASED",
                serverId = 500,
                synced = false, // dirty
            ),
        )

        sync.pullInternal()

        val lists = db.wishlistDao().getAll()
        assertThat(lists).hasSize(1)
        assertThat(lists[0].title).isEqualTo("L")
        assertThat(lists[0].serverId).isNull()
        assertThat(lists[0].synced).isFalse()

        val wishes = db.wishDao().getAll()
        assertThat(wishes).hasSize(1)
        assertThat(wishes[0].serverId).isNull()
        assertThat(wishes[0].synced).isFalse()
    }

    @Test
    fun `pull recreates parent and marks CLEAN children dirty too`() = runTest {
        // Parent synced, один dirty child + один clean child.
        // После pull (parent удалён на сервере) оба ребенка должны быть
        // отсоединены и помечены dirty — иначе clean не попадёт в getUnsynced.
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 50, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "dirty",
                serverId = 500,
                synced = false,
            ),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 11,
                wishlistId = 1,
                title = "clean",
                serverId = 501,
                synced = true,
            ),
        )

        sync.pullInternal()

        val wishes = db.wishDao().getAll().sortedBy { it.id }
        assertThat(wishes).hasSize(2)
        // Оба должны быть unsynced — попадут в следующий push
        assertThat(wishes[0].synced).isFalse()
        assertThat(wishes[0].serverId).isNull()
        assertThat(wishes[1].synced).isFalse()
        assertThat(wishes[1].serverId).isNull()
    }

    @Test
    fun `pull drops tombstone child under deleted parent`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 50, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "tomb",
                serverId = 500,
                synced = false,
                pendingDelete = true,
            ),
        )
        // Любой dirty child чтобы войти в recreate-ветку
        db.wishDao().insertWithId(
            WishEntity(
                id = 11,
                wishlistId = 1,
                title = "dirty",
                serverId = 501,
                synced = false,
            ),
        )

        sync.pullInternal()

        // Tombstone удалён локально (сервер уже не имеет ни parent, ни wish)
        assertThat(db.wishDao().getById(10)).isNull()
        // Dirty child — отсоединён для пересоздания
        assertThat(db.wishDao().getById(11)).isNotNull()
    }

    @Test
    fun `pull cascades deletion when no dirty children exist`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 50, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10,
                wishlistId = 1,
                title = "W",
                serverId = 500,
                synced = true,
            ),
        )

        sync.pullInternal()

        assertThat(db.wishlistDao().getAll()).isEmpty()
        assertThat(db.wishDao().getAll()).isEmpty()
    }

    // ── syncIfLoggedIn retry ─────────────────────────────────────────
    // (баг #14)

    @Test
    fun `syncIfLoggedIn does not mark startup done on transient failure`() = runTest {
        val brokenApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { getWishlists() } throws RuntimeException("network down")
        }
        val brokenSync = SyncManager(db, brokenApi)

        brokenSync.syncIfLoggedIn(isLoggedIn = true)

        assertThat(brokenSync.isStartupSyncDoneForTest()).isFalse()
    }

    @Test
    fun `syncIfLoggedIn does not mark startup done when push leaves dirty rows`() = runTest {
        // Pull проходит, но createWishlist падает → dirty row остаётся.
        val partialApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { getWishlists() } returns emptyList()
            coEvery { createWishlist(any()) } throws RuntimeException("server 500")
        }
        val partialSync = SyncManager(db, partialApi)

        db.wishlistDao().insert(WishlistEntity(title = "L", synced = false))

        partialSync.syncIfLoggedIn(isLoggedIn = true)

        // Push не отправил → startupSyncDone не взведётся, будет retry
        assertThat(partialSync.isStartupSyncDoneForTest()).isFalse()
        assertThat(db.wishlistDao().getUnsynced()).hasSize(1)
    }

    @Test
    fun `syncIfLoggedIn marks startup done on success`() = runTest {
        fakeApi.seed(WishlistDto(id = 1, title = "L"))
        sync.syncIfLoggedIn(isLoggedIn = true)
        assertThat(sync.isStartupSyncDoneForTest()).isTrue()
    }

    @Test
    fun `syncIfLoggedIn skips when not logged in`() = runTest {
        sync.syncIfLoggedIn(isLoggedIn = false)
        assertThat(sync.isStartupSyncDoneForTest()).isFalse()
    }

    // ── clearLocalUnder: атомарность ─────────────────────────────────
    // (баг #19)

    @Test
    fun `clearLocalUnder runs block before wiping data`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "L"))
        var seenData = false

        sync.clearLocalUnder {
            seenData = db.wishlistDao().getAll().isNotEmpty()
        }

        assertThat(seenData).isTrue()
        assertThat(db.wishlistDao().getAll()).isEmpty()
    }

    // ── ownerEmail: привязка rows к аккаунту ─────────────────────────
    // (баг #30 — legacy upgrade leak)

    @Test
    fun `clearLocalIfOwnedByOther wipes Room when foreign rows exist`() = runTest {
        // Room содержит rows от user A
        db.wishlistDao().insert(WishlistEntity(title = "A's list", ownerEmail = "a@x"))
        db.wishDao().insertWithId(
            WishEntity(
                id = 1,
                wishlistId = 1,
                title = "A's wish",
                ownerEmail = "a@x",
            ),
        )
        // Сначала добавим валидный wishlist для FK
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "A's list", ownerEmail = "a@x"),
        )

        // Login как user B
        val wiped = sync.clearLocalIfOwnedByOther("b@x")

        assertThat(wiped).isTrue()
        assertThat(db.wishlistDao().getAll()).isEmpty()
    }

    @Test
    fun `clearLocalIfOwnedByOther keeps Room when rows are guest`() = runTest {
        // Guest rows (ownerEmail = null) — легитимные, не вытираем
        db.wishlistDao().insert(WishlistEntity(title = "guest list", ownerEmail = null))

        val wiped = sync.clearLocalIfOwnedByOther("new@user")

        assertThat(wiped).isFalse()
        assertThat(db.wishlistDao().getAll()).hasSize(1)
    }

    @Test
    fun `clearLocalIfOwnedByOther keeps Room when rows are same email`() = runTest {
        // Re-login тем же юзером — нельзя вытирать
        db.wishlistDao().insert(WishlistEntity(title = "my list", ownerEmail = "same@user"))

        val wiped = sync.clearLocalIfOwnedByOther("same@user")

        assertThat(wiped).isFalse()
        assertThat(db.wishlistDao().getAll()).hasSize(1)
    }

    @Test
    fun `clearLocalIfOwnedByOther wipes legacy rows from previous release`() = runTest {
        // Rows созданы в v1/v2, после migration 2→3 помечены __legacy__.
        // При login любым аккаунтом — вытираем, мы не знаем чьи они.
        db.wishlistDao().insert(
            WishlistEntity(title = "legacy list", ownerEmail = com.nervs.wantly.data.local.WantlyDatabase.LEGACY_OWNER_MARKER),
        )

        val wiped = sync.clearLocalIfOwnedByOther("any@user")

        assertThat(wiped).isTrue()
        assertThat(db.wishlistDao().getAll()).isEmpty()
    }

    @Test
    fun `claimGuestRows binds null-owner rows to the email`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "guest list", ownerEmail = null))
        db.wishlistDao().insert(WishlistEntity(title = "owned list", ownerEmail = "existing@user"))

        sync.claimGuestRows("new@user")

        val all = db.wishlistDao().getAll()
        // Guest row привязан к new email
        assertThat(all.first { it.title == "guest list" }.ownerEmail).isEqualTo("new@user")
        // Уже-привязанный row не трогаем
        assertThat(all.first { it.title == "owned list" }.ownerEmail).isEqualTo("existing@user")
    }
}
