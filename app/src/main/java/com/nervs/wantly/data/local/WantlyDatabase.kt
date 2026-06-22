package com.nervs.wantly.data.local

import android.content.Context
import androidx.room.Database
import androidx.room.Room
import androidx.room.RoomDatabase
import com.nervs.wantly.data.local.entity.WishEntity
import com.nervs.wantly.data.local.entity.WishlistEntity

@Database(
    entities = [WishlistEntity::class, WishEntity::class],
    version = 1,
    exportSchema = false,
)
abstract class WantlyDatabase : RoomDatabase() {
    abstract fun wishlistDao(): WishlistDao
    abstract fun wishDao(): WishDao

    companion object {
        @Volatile
        private var INSTANCE: WantlyDatabase? = null

        fun get(context: Context): WantlyDatabase =
            INSTANCE ?: synchronized(this) {
                INSTANCE ?: Room.databaseBuilder(
                    context.applicationContext,
                    WantlyDatabase::class.java,
                    "wantly.db",
                ).build().also { INSTANCE = it }
            }
    }
}
