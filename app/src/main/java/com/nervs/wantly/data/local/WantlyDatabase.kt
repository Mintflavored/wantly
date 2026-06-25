package com.nervs.wantly.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity

@Database(
    entities = [WishlistEntity::class, WishEntity::class],
    version = 2,
    exportSchema = false,
)
abstract class WantlyDatabase : RoomDatabase() {
    abstract fun wishlistDao(): WishlistDao
    abstract fun wishDao(): WishDao

    companion object {
        @Volatile
        private var INSTANCE: WantlyDatabase? = null

        /**
         * Контекст для миграции: залогинен ли пользователь на момент v1→v2.
         * Устанавливается из AppContainer до открытия БД.
         */
        @Volatile
        @JvmStatic
        var migrationLoggedIn: Boolean = false

        /**
         * Migration 1→2: добавлены serverId, synced, pendingDelete.
         *
         * Schema default ВСЕГДА 0 (совпадает с @ColumnInfo(defaultValue = "0") в entity).
         *
         * v1 НЕ имела server CRUD — локальный PK ≠ серверный ID.
         * v1 register() вызывала migrateLocalToServer(), которая POSTила данные
         * на сервер, но Room строки не очищала. Поэтому мы не можем отличить
         * «данные уже на сервере» от «локальные guest-данные».
         *
         * Решение: очистить Room для ВСЕХ пользователей.
         * - Залогинен → pull при запуске восстановит данные с сервера.
         * - Гость → начинает с чистого листа (v1 logout тоже не чистил таблицы).
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishlists ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE wishlists ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishlists ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE wishes ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")

                db.execSQL("DELETE FROM wishes")
                db.execSQL("DELETE FROM wishlists")
            }
        }

        fun get(context: Context): WantlyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WantlyDatabase::class.java,
                    "wantly.db",
                )
                    .addMigrations(MIGRATION_1_2)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
