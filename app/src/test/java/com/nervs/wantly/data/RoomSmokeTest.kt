package com.nervs.wantly.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import com.nervs.wantly.data.local.WantlyDatabase
import com.nervs.wantly.data.local.entity.WishlistEntity
import kotlinx.coroutines.test.runTest
import org.junit.After
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * Smoke-тест: проверяет что in-memory Room + Robolectric setup работает.
 * Если этот тест падает — весь SyncManagerTest бесполезен.
 */
@RunWith(RobolectricTestRunner::class)
@Config(sdk = [34])
class RoomSmokeTest {
    private lateinit var db: WantlyDatabase

    @Before
    fun setup() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        db = Room.inMemoryDatabaseBuilder(context, WantlyDatabase::class.java)
            .allowMainThreadQueries()
            .build()
    }

    @After
    fun teardown() {
        db.close()
    }

    @Test
    fun `insert wishlist and read back`() = runTest {
        val id = db.wishlistDao().insert(WishlistEntity(title = "test"))
        assertThat(id).isGreaterThan(0L)
        val all = db.wishlistDao().getAll()
        assertThat(all).hasSize(1)
        assertThat(all[0].title).isEqualTo("test")
    }
}
