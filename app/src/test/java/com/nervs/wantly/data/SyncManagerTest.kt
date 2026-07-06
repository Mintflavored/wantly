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

    // ── Regression: edit во время POST не должен теряться (create path) ─────
    // (codex P2 — "Preserve list/wish edits made during first sync")
    //
    // Тестируем snapshot-aware setServerIdPreservingDirty напрямую: это DAO-метод,
    // который SyncManager вызывает после POST. Если поля изменились пока POST был
    // в полёте, synced должен остаться false → следующий push PATCHит edit.
    @Test
    fun `setServerIdPreservingDirty keeps wishlist dirty if fields changed during POST`() = runTest {
        val localId = db.wishlistDao().insert(
            WishlistEntity(title = "Original", description = null, coverColor = 0, synced = false),
        )
        // Симулируем: snapshot захвачен до POST (Original/null/0), но пока POST летел,
        // юзер отредактировал поля в Room.
        db.wishlistDao().updateEditableFields(localId, "Edited", "new desc", 3)

        db.wishlistDao().setServerIdPreservingDirty(
            localId = localId,
            serverId = 100,
            expectedTitle = "Original",
            expectedCoverColor = 0,
            expectedDescription = null,
        )

        val saved = db.wishlistDao().getById(localId)!!
        assertThat(saved.serverId).isEqualTo(100L) // serverId сохранён всегда
        assertThat(saved.synced).isFalse() // но dirty — edit не потерян
        assertThat(saved.title).isEqualTo("Edited") // поля не перетёрты snapshot'ом
    }

    @Test
    fun `setServerIdPreservingDirty keeps wish dirty if fields changed during POST`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "L", serverId = 1, synced = true))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Original", url = null, price = null, synced = false),
        )
        // Edit в полёте: title/url изменились, status тот же.
        db.wishDao().updateEditableFields(
            id = wishId, title = "Edited", description = null,
            url = "https://shop/item", imageUrl = null, price = 99.0,
            currency = "RUB", storeName = null,
        )

        db.wishDao().setServerIdPreservingDirty(
            localId = wishId,
            serverId = 500,
            expectedTitle = "Original",
            expectedDescription = null,
            expectedUrl = null,
            expectedImageUrl = null,
            expectedPrice = null,
            expectedCurrency = "RUB",
            expectedStoreName = null,
            expectedStatus = "WANTED",
        )

        val saved = db.wishDao().getById(wishId)!!
        assertThat(saved.serverId).isEqualTo(500L) // serverId сохранён всегда
        assertThat(saved.synced).isFalse() // dirty — edit не потерян, след. push PATCHит
        assertThat(saved.title).isEqualTo("Edited")
        assertThat(saved.url).isEqualTo("https://shop/item")
    }

    // ── Regression: textDirty должен сбрасываться после успешного create POST ──
    // (codex P2 — "Clear textDirty after a create POST succeeds")
    //
    // Сценарий: локально создал wish, отредактировал поля до sync (textDirty=1),
    // POST отправил актуальные поля, snapshot совпал → row стал synced. textDirty
    // должен сброситься в 0, иначе следующий cycle-status опять шлёт full PATCH
    // и перезаписывает чужие field-правки.
    @Test
    fun `setServerIdPreservingDirty clears textDirty when POST snapshot matches`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "L", serverId = 1, synced = true))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Original", synced = false, textDirty = false),
        )
        // Локально отредактировали поля до первого push (textDirty выставляется).
        db.wishDao().updateEditableFields(
            id = wishId, title = "Edited", description = null, url = "https://shop/item",
            imageUrl = null, price = 99.0, currency = "RUB", storeName = null,
        )
        // POST отправил именно это состояние → snapshot в setServerIdPreservingDirty
        // совпадает с актуальным row.
        db.wishDao().setServerIdPreservingDirty(
            localId = wishId,
            serverId = 500,
            expectedTitle = "Edited",
            expectedDescription = null,
            expectedUrl = "https://shop/item",
            expectedImageUrl = null,
            expectedPrice = 99.0,
            expectedCurrency = "RUB",
            expectedStoreName = null,
            expectedStatus = "WANTED",
        )

        val saved = db.wishDao().getById(wishId)!!
        assertThat(saved.serverId).isEqualTo(500L)
        assertThat(saved.synced).isTrue() // POST успешен, snapshot совпал
        assertThat(saved.textDirty).isFalse() // textDirty сброшен — follow-up cycle-status пойдёт узким /status
    }

    // ── Regression: textDirty должен сохраняться при неудачной snapshot-check ──
    // (codex P2 — "Keep textDirty when snapshot check fails")
    //
    // Сценарий: PATCH в полёте, юзер редактирует wish второй раз. clearTextDirtyIfUnchanged
    // вызывается со snapshot'ом ДО второго edit → CASE падает в ELSE. textDirty должен
    // остаться 1, иначе drain посчитает row status-only и не отправит field-правку.
    @Test
    fun `clearTextDirtyIfUnchanged keeps textDirty when snapshot diverged`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "L", serverId = 1, synced = true))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "Original", serverId = 500, synced = false),
        )
        db.wishDao().updateEditableFields(
            id = wishId, title = "First edit", description = null, url = null,
            imageUrl = null, price = null, currency = "RUB", storeName = null,
        )
        // Пока PATCH был в полёте (со snapshot "First edit"), юзер отредактировал снова.
        db.wishDao().updateEditableFields(
            id = wishId, title = "Second edit", description = null, url = null,
            imageUrl = null, price = null, currency = "RUB", storeName = null,
        )

        // SyncManager вызвал бы это с устаревшим snapshot "First edit".
        db.wishDao().clearTextDirtyIfUnchanged(
            id = wishId,
            expectedTitle = "First edit", // не совпадает с актуальным "Second edit"
            expectedDescription = null,
            expectedUrl = null,
            expectedImageUrl = null,
            expectedPrice = null,
            expectedCurrency = "RUB",
            expectedStoreName = null,
            expectedStatus = "WANTED",
        )

        val saved = db.wishDao().getById(wishId)!!
        assertThat(saved.synced).isFalse() // dirty — row не синхронизирован
        assertThat(saved.textDirty).isTrue() // textDirty сохранён → след. push шлёт full PATCH
        assertThat(saved.title).isEqualTo("Second edit") // актуальное поле не перетёрто
    }

    // ── Regression: cycle-status в окне full PATCH не должен держать textDirty ──
    // (codex P2 — "Clear textDirty for status-only follow-up updates")
    //
    // Сценарий: full PATCH в полёте (field-правки уже ушли на сервер), юзер
    // только cycle-status жмёт → локальный status разошёлся с snapshot'ом.
    // textDirty должен сброситься (field-правки доставлены), synced остаться 0
    // (status разошёлся → нужен follow-up узкий /status). Иначе drain снова
    // шлёт full PATCH и перезаписывает чужие field-правки, сделанные после PATCH.
    @Test
    fun `clearTextDirtyIfUnchanged clears textDirty on status-only divergence`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "L", serverId = 1, synced = true))
        val wishId = db.wishDao().insert(
            WishEntity(wishlistId = listId, title = "W", serverId = 500, synced = false, textDirty = true),
        )
        // Пока PATCH был в полёте (field-snapshot "W"), юзер cycle-status.
        db.wishDao().updateStatus(wishId, "PURCHASED")

        db.wishDao().clearTextDirtyIfUnchanged(
            id = wishId,
            expectedTitle = "W", // field-snapshot совпадает
            expectedDescription = null,
            expectedUrl = null,
            expectedImageUrl = null,
            expectedPrice = null,
            expectedCurrency = "RUB",
            expectedStoreName = null,
            expectedStatus = "WANTED", // не совпадает с актуальным "PURCHASED"
        )

        val saved = db.wishDao().getById(wishId)!!
        assertThat(saved.synced).isFalse() // status разошёлся → row ещё dirty
        assertThat(saved.textDirty).isFalse() // field-правки доставлены → флаг сброшен
        assertThat(saved.status).isEqualTo("PURCHASED") // актуальный status на месте
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

    // ── Regression: HTTP 400 → syncError, не блокирует logout ─────────────
    // (fixes codex P1 из PR #9 — legacy dirty wishes retrying forever)

    @Test
    fun `pushPending marks wish with syncError on 400 update`() = runTest {
        // Существующий synced wish → редактируется (textDirty=true → full PATCH) → сервер 400.
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "W", serverId = 500, synced = false, textDirty = true),
        )
        val rejectingApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { updateWish(any(), any()) } throws com.nervs.wantly.data.remote.ApiException(400, "validation")
        }
        val rejectingSync = SyncManager(db, rejectingApi)

        rejectingSync.pushPending()

        // Row помечен syncError + synced → выпадает из getUnsynced, не retry'ится.
        val saved = db.wishDao().getById(10)!!
        assertThat(saved.syncError).isTrue()
        assertThat(saved.synced).isTrue()
        assertThat(db.wishDao().getUnsynced()).isEmpty()
    }

    @Test
    fun `pushPending marks wishlist with syncError on 400 create`() = runTest {
        db.wishlistDao().insert(WishlistEntity(title = "L", synced = false))
        val rejectingApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { createWishlist(any()) } throws com.nervs.wantly.data.remote.ApiException(400, "validation")
        }
        val rejectingSync = SyncManager(db, rejectingApi)

        rejectingSync.pushPending()

        val saved = db.wishlistDao().getAll().first()
        assertThat(saved.syncError).isTrue()
        assertThat(saved.synced).isTrue()
        assertThat(db.wishlistDao().getUnsynced()).isEmpty()
    }

    @Test
    fun `pushPendingVerifiedForLogout returns SUCCESS when only syncError rows remain`() = runTest {
        // Row уже помечен syncError (как будто предыдущий push получил 400).
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true, syncError = true),
        )
        // API не вызывается — нет unsynced rows.
        val outcome = sync.pushPendingVerifiedForLogout()

        assertThat(outcome).isEqualTo(LogoutSyncOutcome.SUCCESS)
        assertThat(db.wishlistDao().getUnsynced()).isEmpty()
    }

    @Test
    fun `editing a syncError wish clears the flag and marks dirty for resync`() = runTest {
        val listId = db.wishlistDao().insert(WishlistEntity(title = "L", serverId = 1, synced = true))
        val wishId = db.wishDao().insert(
            WishEntity(
                wishlistId = listId, title = "Old", serverId = 500,
                synced = true, syncError = true,
            ),
        )
        val repo = com.nervs.wantly.data.repository.WishlistRepository(
            wishlistDao = db.wishlistDao(),
            wishDao = db.wishDao(),
            linkPreviewService = mockk(relaxed = true),
            api = api,
            sessionManager = mockk(relaxed = true),
        )

        repo.updateWish(
            db.wishDao().getById(wishId)!!,
            com.nervs.wantly.data.model.WishDraft(title = "Fixed"),
        )

        val saved = db.wishDao().getById(wishId)!!
        assertThat(saved.syncError).isFalse()
        assertThat(saved.synced).isFalse()
        assertThat(saved.title).isEqualTo("Fixed")
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

        // Юзер меняет статус → dirty (textDirty не выставляется — только status)
        db.wishDao().updateStatus(10, "PURCHASED")

        sync.pushPending()

        // Сервер получил PATCH /status (узкий — не трогает field-правки других клиентов)
        assertThat(fakeApi.wish(500)!!.status).isEqualTo("PURCHASED")
        // Локальная запись снова synced
        assertThat(db.wishDao().getById(10)!!.synced).isTrue()
    }

    // ── Regression: cycle-status не должен перезаписывать чужие field-правки ──
    // (codex P2 — "Preserve remote fields on status-only sync")
    //
    // Сценарий: wish есть локально и на сервере. Другой клиент отредактировал
    // title на сервере ("Remote edited"). Этот юзер только cycle-status жмёт.
    // textDirty=false → push должен слать узкий /status, НЕ полный PATCH с
    // устаревшим title="W". Иначе чужая правка затёрлась бы.
    @Test
    fun `pushPending cycle-status uses narrow PATCH and does not overwrite remote fields`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "W", serverId = 500, synced = true),
        )
        // Локальный title="W" (устаревший — на сервере уже "Remote edited").
        fakeApi.seed(
            WishlistDto(id = 100, title = "L"),
            listOf(
                com.nervs.wantly.data.remote.dto.WishDto(
                    id = 500, wishlistId = 100, title = "Remote edited", status = "WANTED",
                ),
            ),
        )

        // Юзер только cycle-status (textDirty не выставляется).
        db.wishDao().updateStatus(10, "PURCHASED")
        sync.pushPending()

        // Узкий /status обновил только status; field-правка другого клиента жива.
        val serverWish = fakeApi.wish(500)!!
        assertThat(serverWish.status).isEqualTo("PURCHASED")
        assertThat(serverWish.title).isEqualTo("Remote edited")
        // textDirty не выставлялся → локальный title не отправлялся как field-правка.
        assertThat(db.wishDao().getById(10)!!.textDirty).isFalse()
    }

    @Test
    fun `pushPending PATCHes existing wishlist with new fields`() = runTest {
        // synced wishlist → юзер редактирует title → dirty → push шлёт PATCH
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "Old", description = null, coverColor = 0, serverId = 100, synced = true),
        )
        fakeApi.seed(WishlistDto(id = 100, title = "Old", coverColor = 0))

        // Юзер редактирует → dirty
        val existing = db.wishlistDao().getById(1)!!
        db.wishlistDao().update(
            existing.copy(title = "New title", description = "desc", coverColor = 3, synced = false),
        )

        sync.pushPending()

        // Сервер получил обновлённые поля
        val serverList = fakeApi.wishlist(100)!!
        assertThat(serverList.title).isEqualTo("New title")
        assertThat(serverList.description).isEqualTo("desc")
        assertThat(serverList.coverColor).isEqualTo(3)
        // Локальная запись снова synced, serverId не изменился
        val saved = db.wishlistDao().getById(1)!!
        assertThat(saved.synced).isTrue()
        assertThat(saved.serverId).isEqualTo(100)
    }

    @Test
    fun `pushPending PATCHes existing wish with new fields`() = runTest {
        // synced wishlist + wish → юзер редактирует url/price → push шлёт PATCH
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(
                id = 10, wishlistId = 1, title = "W", url = null, price = null,
                serverId = 500, synced = true,
            ),
        )
        fakeApi.seed(
            WishlistDto(id = 100, title = "L"),
            listOf(
                com.nervs.wantly.data.remote.dto.WishDto(
                    id = 500, wishlistId = 100, title = "W", status = "WANTED",
                ),
            ),
        )

        // Юзер редактирует → dirty + textDirty (через Repository.updateWish / DAO
        // updateEditableFields). Push шлёт полный PATCH, не узкий /status.
        db.wishDao().updateEditableFields(
            id = 10,
            title = "W",
            description = null,
            url = "https://shop.example/item",
            imageUrl = null,
            price = 1999.0,
            currency = "RUB",
            storeName = null,
        )

        sync.pushPending()

        // Сервер получил обновлённые поля
        val serverWish = fakeApi.wish(500)!!
        assertThat(serverWish.url).isEqualTo("https://shop.example/item")
        assertThat(serverWish.price).isEqualTo(1999.0)
        // Локальная запись снова synced, textDirty сброшен
        val saved = db.wishDao().getById(10)!!
        assertThat(saved.synced).isTrue()
        assertThat(saved.textDirty).isFalse()
    }

    // ── Regression: markSynced не должен затирать dirty flag второго edit ────
    // (codex P2 — "Do not clear newer wish edits after PATCH")
    @Test
    fun `pushPending wish PATCH does not clear newer text edit in flight`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "Old", serverId = 500, synced = true),
        )
        fakeApi.seed(
            WishlistDto(id = 100, title = "L"),
            listOf(
                com.nervs.wantly.data.remote.dto.WishDto(
                    id = 500, wishlistId = 100, title = "Old", status = "WANTED",
                ),
            ),
        )

        // Юзер меняет title → dirty + textDirty → push (PATCH в полёте)
        db.wishDao().updateEditableFields(
            id = 10, title = "First edit", description = null, url = null,
            imageUrl = null, price = null, currency = "RUB", storeName = null,
        )

        sync.pushPending()

        // Пока PATCH был в полёте, симулируем второй edit (price/status те же).
        // Без snapshot-aware markSynced dirty flag затёрся бы и edit потерялся.
        db.wishDao().updateEditableFields(
            id = 10, title = "Second edit", description = null, url = null,
            imageUrl = null, price = null, currency = "RUB", storeName = null,
        )

        sync.pushPending()

        // Сервер получил второй edit (не остался висеть в dirty-limbo).
        assertThat(fakeApi.wish(500)!!.title).isEqualTo("Second edit")
        assertThat(db.wishDao().getById(10)!!.synced).isTrue()
    }

    // ── Regression: wishlist PATCH 404 должен отсоединять serverId ──────────
    // (codex P2 — "Detach lists when PATCH returns 404")
    @Test
    fun `pushPending wishlist PATCH 404 detaches serverId and recreates via POST`() = runTest {
        // Список есть локально с serverId=100, но удалён на сервере → PATCH 404.
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = false),
        )
        // fakeApi НЕ seed'ит list 100 → updateWishlist бросит 404.

        sync.pushPending()

        // pushPending крутит drain-loop: на 404 сбрасывает serverId и сразу
        // POST'ит локальное состояние в том же вызове → список воссоздан
        // с новым serverId (не 100) и помечен synced.
        val saved = db.wishlistDao().getById(1)!!
        assertThat(saved.serverId).isNotNull()
        assertThat(saved.serverId).isNotEqualTo(100L)
        assertThat(saved.synced).isTrue()
        assertThat(fakeApi.wishlistIds()).containsExactly(saved.serverId)
        assertThat(fakeApi.wishlist(saved.serverId!!)!!.title).isEqualTo("L")
    }

    // ── Regression: wishlist PATCH 404 должен отсоединять и clean детей ────
    // (codex P1 — "Detach child wishes when recreating deleted lists")
    @Test
    fun `pushPending wishlist PATCH 404 detaches clean children for recreation`() = runTest {
        // parent dirty (edit), child clean (synced=true со старым serverId=600).
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = false),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "W", serverId = 600, synced = true),
        )
        // fakeApi НЕ seed'ит ничего → parent PATCH 404.

        sync.pushPending()

        // drain-loop: parent пересоздан через POST, child тоже POST'нут под новым
        // parent serverId (не потерян, не остался orphan со старым serverId=600).
        val savedList = db.wishlistDao().getById(1)!!
        assertThat(savedList.serverId).isNotEqualTo(100L)

        val savedWish = db.wishDao().getById(10)!!
        assertThat(savedWish.serverId).isNotNull()
        assertThat(savedWish.serverId).isNotEqualTo(600L)
        assertThat(savedWish.synced).isTrue()
        // Сервер получил ровно один список и один wish, привязанный к нему.
        assertThat(fakeApi.wishlistIds()).containsExactly(savedList.serverId)
        assertThat(fakeApi.wishIds()).containsExactly(savedWish.serverId)
        assertThat(fakeApi.wish(savedWish.serverId!!)!!.wishlistId).isEqualTo(savedList.serverId)
    }

    // ── Regression: detach при 404 не должен затирать concurrent delete ──────
    // (codex P2 — "Preserve local deletes when detaching a 404 list")
    //
    // Сценарий: PATCH в полёте, юзер удаляет список (pendingDelete=true).
    // PATCH возвращает 404 → detachParentAndChildren. Без partial detach
    // full-row update затёр бы pendingDelete=false → drain POSTнул бы список
    // вместо DELETE. С partial detach pendingDelete сохраняется → drain DELETE'т.
    @Test
    fun `pushPending wishlist PATCH 404 preserves concurrent delete over detach`() = runTest {
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = false),
        )
        // PATCH в полёте (симуляция): пока он летел, юзер удалил список.
        // markDeleted ставит pendingDelete=1, synced=0.
        db.wishlistDao().markDeleted(1)
        // fakeApi НЕ seed'ит list 100 → PATCH 404 → detachParentAndChildren.

        sync.pushPending()

        // Список удалён окончательно (tombstone обработан в drain), не пересоздан.
        assertThat(db.wishlistDao().getById(1)).isNull()
        // Сервер НЕ получил новый список (не было POST'а после detach).
        assertThat(fakeApi.wishlistIds()).isEmpty()
    }

    // ── Regression: edit не должен откатывать свежий serverId ────────────────
    // (codex P2 — "Preserve fresh server IDs when saving wish edits")
    //
    // Сценарий: UI открыл edit-screen для нового wish (serverId ещё null),
    // background-sync успел проставить serverId=600, потом UI вызывает save.
    // updateEditableFields должен сохранить свежий serverId (не откатить в null),
    // иначе след. push POST'нет редактирование как дубль.
    @Test
    fun `wish edit preserves serverId assigned by background sync`() = runTest {
        // UI-снапшот: serverId ещё null (wish только что создан локально).
        db.wishlistDao().insertWithId(
            WishlistEntity(id = 1, title = "L", serverId = 100, synced = true),
        )
        db.wishDao().insertWithId(
            WishEntity(id = 10, wishlistId = 1, title = "Old", serverId = null, synced = false),
        )
        // Background-sync проставил serverId параллельно.
        db.wishDao().setServerId(10, 600)
        // Проверка precondition: в Room serverId=600, а UI-снапшот мог быть устаревшим.
        assertThat(db.wishDao().getById(10)!!.serverId).isEqualTo(600L)

        // Repository.updateWish — partial update через updateEditableFields,
        // не full-row copy. Передаём устаревший snapshot из ViewModel (serverId=null
        // в Kotlin-объекте не используется — метод работает по id).
        val staleSnapshot = db.wishDao().getById(10)!!.copy(serverId = null, title = "Old")
        val repo = com.nervs.wantly.data.repository.WishlistRepository(
            wishlistDao = db.wishlistDao(),
            wishDao = db.wishDao(),
            linkPreviewService = mockk(relaxed = true),
            api = api,
            sessionManager = mockk(relaxed = true),
        )
        repo.updateWish(staleSnapshot, com.nervs.wantly.data.model.WishDraft(title = "Edited"))

        // serverId не откатился в null; поля обновились; synced=false (dirty для push).
        val saved = db.wishDao().getById(10)!!
        assertThat(saved.serverId).isEqualTo(600L)
        assertThat(saved.title).isEqualTo("Edited")
        assertThat(saved.synced).isFalse()
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
        // PATCH вернёт 404 → wish отсоединяется, планируется retry pass,
        // второй pass POSTит под текущим parent.
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
        // После retry pass: POST создал новый wish на сервере
        assertThat(saved!!.serverId).isNotNull()
        assertThat(saved.serverId).isNotEqualTo(500L) // не старый удалённый
        assertThat(saved.synced).isTrue()
        assertThat(fakeApi.wishIds()).hasSize(1)
    }

    @Test
    fun `pushPending createWish 404 detaches parent serverId for recreate`() = runTest {
        // Локальный wish под списком, который удалён на сервере.
        // POST wish вернёт 404 (parent не найден) → parent и child отсоединяются,
        // планируется второй проход → parent и child POST'ятся под новым serverId.
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

        // После 404 push делает второй проход: parent POST → новый serverId,
        // wish POST под новым parent. Проверяем что данные дошли до сервера.
        assertThat(fakeApi.wishlistIds()).hasSize(1)
        assertThat(fakeApi.wishIds()).hasSize(1)
        // Локальный parent теперь synced с новым serverId
        val parent = db.wishlistDao().getById(1)
        assertThat(parent).isNotNull()
        assertThat(parent!!.serverId).isNotNull()
        assertThat(parent.serverId).isNotEqualTo(100L) // не старый удалённый
        assertThat(parent.synced).isTrue()
    }

    @Test
    fun `pushPending createWish 404 detaches CLEAN siblings too`() = runTest {
        // Parent + dirty wish (триггерит createWish, который упадёт 404) +
        // clean sibling (после detach становится dirty и тоже POST'ится).
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

        // После 404 (pass 1: detach + flag) → pass 2: parent POST + оба wish POST.
        // На сервере: 1 wishlist + 2 wishes (clean sibling не потерялся).
        assertThat(fakeApi.wishlistIds()).hasSize(1)
        assertThat(fakeApi.wishIds()).hasSize(2)

        // Локально: всё synced
        val parent = db.wishlistDao().getById(1)
        assertThat(parent!!.serverId).isNotNull()
        assertThat(parent.synced).isTrue()

        val cleanSibling = db.wishDao().getById(11)
        assertThat(cleanSibling!!.synced).isTrue()
        assertThat(cleanSibling.serverId).isNotNull()
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
    fun `syncIfLoggedIn wipes foreign rows before pull on startup`() = runTest {
        // Юзер с сохранённым token, но в Room есть rows от ДРУГОГО email
        // (например сменил аккаунт без logout). Push не должен отправить их.
        val ownerApi = mockk<WantlyApi>(relaxed = true) {
            coEvery { getWishlists() } returns emptyList()
        }
        val ownerSync = SyncManager(db, ownerApi, emailProvider = { "current@user" })

        db.wishlistDao().insert(
            WishlistEntity(title = "foreign list", ownerEmail = "other@user"),
        )

        ownerSync.syncIfLoggedIn(isLoggedIn = true)

        // Foreign rows вытерты перед pull
        assertThat(db.wishlistDao().getAll()).isEmpty()
    }

    @Test
    fun `backfillOwnerEmailIfFirstRun binds existing rows to current logged-in email`() = runTest {
        // Upgrade-сценарий: юзер был залогинен, rows не имеют ownerEmail (NULL).
        val ownerSync = SyncManager(db, api, emailProvider = { "me@user" })
        db.wishlistDao().insert(WishlistEntity(title = "list", ownerEmail = null))

        val didBackfill = ownerSync.backfillOwnerEmailIfFirstRun(isBackfillDone = false)

        assertThat(didBackfill).isTrue()
        val row = db.wishlistDao().getAll().first()
        assertThat(row.ownerEmail).isEqualTo("me@user")
    }

    @Test
    fun `backfillOwnerEmailIfFirstRun skips guest upgrade`() = runTest {
        // Юзер был guest при апгрейде → token/email null → rows остаются NULL
        val guestSync = SyncManager(db, api, emailProvider = { null })
        db.wishlistDao().insert(WishlistEntity(title = "guest list", ownerEmail = null))

        val didBackfill = guestSync.backfillOwnerEmailIfFirstRun(isBackfillDone = false)

        // Гость — backfill не делается, rows остаются NULL (привяжутся при login)
        assertThat(didBackfill).isFalse()
        assertThat(db.wishlistDao().getAll().first().ownerEmail).isNull()
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
    fun `clearLocalIfOwnedByOther wipes foreign rows from previous release`() = runTest {
        // Rows созданы в v1/v2, после миграции ownerEmail=NULL. Если юзер
        // был залогинен при апгрейде — backfill помечает их его email.
        // Если guest — остаются NULL и привяжутся при login через claimGuestRows.
        // Здесь симулируем rows от ДРУГОГО email (после re-login без logout).
        db.wishlistDao().insert(
            WishlistEntity(title = "foreign list", ownerEmail = "other@user"),
        )

        val wiped = sync.clearLocalIfOwnedByOther("me@user")

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
