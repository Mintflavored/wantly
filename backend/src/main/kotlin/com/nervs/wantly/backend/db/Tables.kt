package com.nervs.wantly.backend.db

import org.jetbrains.exposed.sql.ReferenceOption
import org.jetbrains.exposed.sql.Table
import org.jetbrains.exposed.sql.kotlin.datetime.timestamp
import kotlinx.datetime.Clock

object Users : Table("users") {
    val id = long("id").autoIncrement()
    val email = varchar("email", 255).uniqueIndex()
    val passwordHash = varchar("password_hash", 255)
    val displayName = varchar("display_name", 100).nullable()
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)
}

object Wishlists : Table("wishlists") {
    val id = long("id").autoIncrement()
    val ownerId = long("owner_id").references(Users.id)
    val title = varchar("title", 200)
    val description = text("description").nullable()
    val isShared = bool("is_shared").default(false)
    val coverColor = integer("cover_color").default(0)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)

    init {
        // Без explicit index Exposed/PostgreSQL не создаёт его автоматически
        // для FK — каждый SELECT WHERE owner_id = ? шёл бы seq scan.
        // Сам index создаётся Flyway V1__init.sql; здесь дублируем для
        // документации и на случай если SchemaUtils.create снова включат.
        index("idx_wishlists_owner_id", false, ownerId)
    }
}

object Wishes : Table("wishes") {
    val id = long("id").autoIncrement()
    val wishlistId = long("wishlist_id").references(Wishlists.id, onDelete = ReferenceOption.CASCADE)
    val title = varchar("title", 500)
    val description = text("description").nullable()
    val url = text("url").nullable()
    val imageUrl = text("image_url").nullable()
    val price = double("price").nullable()
    val currency = varchar("currency", 3).default("RUB")
    val storeName = varchar("store_name", 200).nullable()
    val status = varchar("status", 20).default("WANTED")
    val sortOrder = integer("sort_order").default(0)
    val createdAt = timestamp("created_at").clientDefault { Clock.System.now() }
    override val primaryKey = PrimaryKey(id)

    init {
        index("idx_wishes_wishlist_id", false, wishlistId)
    }
}
