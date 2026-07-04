package com.nervs.wantly.backend.db

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.flywaydb.core.Flyway
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction
import javax.sql.DataSource

object DatabaseFactory {

    /** Подключение к БД через HikariCP пул + запуск Flyway миграций. */
    fun init(url: String, user: String, password: String): DataSource {
        val ds = HikariDataSource(HikariConfig().apply {
            jdbcUrl = url
            username = user
            this.password = password
            driverClassName = "org.postgresql.Driver"
            poolName = "wantly-backend-pool"
            maximumPoolSize = 10
            minimumIdle = 2
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_READ_COMMITTED"
            connectionTimeout = 10_000 // 10s до ошибки если pool исчерпан
            idleTimeout = 60_000
            maxLifetime = 30 * 60_000 // 30 min
            validate()
        })
        Database.connect(ds)

        // Flyway миграции. baselineOnMigrate=true — если БД уже содержит
        // таблицы (например, созданные через SchemaUtils.create в старом
        // релизе), Flyway не падает, а бейзлайнит существующую схему и
        // применяет только новые миграции поверх.
        Flyway.configure()
            .dataSource(ds)
            .baselineOnMigrate(true)
            .baselineVersion("0")
            .locations("classpath:db/migration")
            .load()
            .migrate()

        return ds
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}
