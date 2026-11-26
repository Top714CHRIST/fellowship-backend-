package com.example

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import kotlinx.coroutines.Dispatchers
import org.jetbrains.exposed.sql.Database
import org.jetbrains.exposed.sql.transactions.experimental.newSuspendedTransaction

object DatabaseFactory {
    fun init() {
        val jdbcUrl = System.getenv("DB_JDBC_URL")
            ?: error("Missing DB_JDBC_URL environment variable")
        val dbUser = System.getenv("DB_USER")
            ?: error("Missing DB_USER environment variable")
        val dbPassword = System.getenv("DB_PASSWORD")
            ?: error("Missing DB_PASSWORD environment variable")

        val config = HikariConfig().apply {
            driverClassName = "org.postgresql.Driver"
            this.jdbcUrl = jdbcUrl
            username = dbUser
            password = dbPassword
            maximumPoolSize = 5
            minimumIdle = 1
            idleTimeout = 60_000
            maxLifetime = 300_000
            keepaliveTime = 30_000
            isAutoCommit = false
            transactionIsolation = "TRANSACTION_REPEATABLE_READ"
            validate()
        }

        Database.connect(HikariDataSource(config))
    }

    suspend fun <T> dbQuery(block: suspend () -> T): T =
        newSuspendedTransaction(Dispatchers.IO) { block() }
}