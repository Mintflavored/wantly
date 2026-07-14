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
    version = 6,
    exportSchema = false,
)
abstract class WantlyDatabase : RoomDatabase() {
    abstract fun wishlistDao(): WishlistDao
    abstract fun wishDao(): WishDao

    companion object {
        @Volatile
        private var INSTANCE: WantlyDatabase? = null

        /**
         * Migration 1→2: добавлены serverId, synced, pendingDelete.
         *
         * Schema default ВСЕГДА 0 (совпадает с @ColumnInfo(defaultValue = "0") в entity).
         *
         * Все v1-строки (guest и залогиненные) сохраняются с serverId=NULL,
         * synced=0. Startup sync отправит их на сервер как новые.
         *
         * Раньше для залогиненного юзера делался DELETE с расчетом, что pull
         * восстановит данные с сервера. Но v1 не имела полноценного sync-движка,
         * поэтому данные могли существовать только локально → DELETE терял их
         * безвозвратно, особенно если первый post-upgrade pull уходил офлайн.
         */
        private val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishlists ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE wishlists ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishlists ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN serverId INTEGER")
                db.execSQL("ALTER TABLE wishes ADD COLUMN synced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN pendingDelete INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 2→3: добавлен ownerEmail (nullable, без default — существующие
         * строки получают NULL, что означает «guest / не привязан».
         *
         * Backfill email проставляется отдельно в WantlyApp.onCreate при первом
         * запуске после миграции, на основе сохранённого token: если юзер был
         * залогинен — его rows помечаются его email; если guest — остаются NULL.
         */
        private val MIGRATION_2_3 = object : Migration(2, 3) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishlists ADD COLUMN ownerEmail TEXT")
                db.execSQL("ALTER TABLE wishes ADD COLUMN ownerEmail TEXT")
            }
        }

        /**
         * Migration 3→4: добавлен wishes.textDirty (BOOLEAN DEFAULT 0).
         *
         * Гранулярный dirty-флаг для разделения full PATCH (полевые правки) и
         * узкого PATCH /status (cycle-status). Без него push всегда слал бы
         * полный PATCH и перезаписывал field-правки других клиентов при cycle-status.
         * Существующие строки получают textDirty=0 — корректно: всё, что уже в Room,
         * либо synced, либо помечено status-only изменением через updateStatus.
         */
        private val MIGRATION_3_4 = object : Migration(3, 4) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishes ADD COLUMN textDirty INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 4→5: добавлен syncError (BOOLEAN DEFAULT 0) на обе таблицы.
         *
         * Флаг terminal-ошибки синхронизации: HTTP 400 от сервера → row помечается
         * synced=1 + syncError=1, перестаёт retry'иться и не блокирует logout.
         * Существующие строки получают syncError=0 — корректно (нет ошибки).
         */
        private val MIGRATION_4_5 = object : Migration(4, 5) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishlists ADD COLUMN syncError INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN syncError INTEGER NOT NULL DEFAULT 0")
            }
        }

        /**
         * Migration 5→6: добавлен preDeleteSynced (BOOLEAN DEFAULT 0) на обе таблицы.
         *
         * Снимок synced на момент markDeleted. restoreDeleted (undo удаления)
         * восстанавливает synced из этого снимка — иначе undo либо терял pending
         * edits (synced=1 для already-dirty row), либо no-op PATCH перезаписывал
         * remote changes (synced=0 для already-synced row). Существующие строки
         * получают preDeleteSynced=0 — корректно (они не pendingDelete).
         */
        private val MIGRATION_5_6 = object : Migration(5, 6) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE wishlists ADD COLUMN preDeleteSynced INTEGER NOT NULL DEFAULT 0")
                db.execSQL("ALTER TABLE wishes ADD COLUMN preDeleteSynced INTEGER NOT NULL DEFAULT 0")
            }
        }

        /** Маркер в DataStore, что backfill ownerEmail после миграции выполнен. */
        const val BACKFILL_KEY = "v3_owner_backfill_done"

        fun get(context: Context): WantlyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WantlyDatabase::class.java,
                    "wantly.db",
                )
                    .addMigrations(MIGRATION_1_2, MIGRATION_2_3, MIGRATION_3_4, MIGRATION_4_5, MIGRATION_5_6)
                    .build()
                    .also { INSTANCE = it }
            }
    }
}
