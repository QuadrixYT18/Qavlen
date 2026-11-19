package de.quadrixyt.qavlen.storage

import com.zaxxer.hikari.HikariConfig
import com.zaxxer.hikari.HikariDataSource
import java.sql.Connection
import java.sql.PreparedStatement
import java.sql.ResultSet
import java.sql.SQLException
import java.sql.Statement
import java.sql.Timestamp
import java.time.Duration
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.BooleanSupplier
import javax.sql.DataSource

/** Maps the current row of a result set. Do not advance the result set. */
public fun interface RowMapper<T> {
    @Throws(SQLException::class)
    public fun map(row: ResultSet): T
}

/** Work that runs inside a transaction. */
public fun interface TransactionWork<T> {
    @Throws(SQLException::class)
    public fun run(transaction: SqlSession): T
}

/** A database operation failed. The SQL state and vendor code of the cause are kept. */
public class DatabaseException(message: String, cause: SQLException) : RuntimeException(message, cause) {
    public val sqlState: String? = cause.sqlState
}

/**
 * Executes SQL with prepared statements. Parameters are always bound, never concatenated, so values cannot
 * change the meaning of a statement.
 */
public interface SqlSession {
    public fun <T> query(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): List<T>

    public fun <T> queryFirst(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): T? =
        query(sql, mapper, *parameters).firstOrNull()

    /** Executes an `INSERT`, `UPDATE`, `DELETE` or DDL statement and returns the affected row count. */
    public fun update(sql: String, vararg parameters: Any?): Int

    /** Executes an `INSERT` and returns the first generated key, or `null` if none was generated. */
    public fun insert(sql: String, vararg parameters: Any?): Long?

    /** Executes [sql] once per parameter row in a single JDBC batch. */
    public fun batch(sql: String, rows: List<List<Any?>>): IntArray
}

/**
 * A pooled SQL database for MariaDB, PostgreSQL or any JDBC driver on the classpath.
 *
 * Synchronous methods block the calling thread; the `Async` variants run on a dedicated executor that is sized to
 * the connection pool, so queued work waits in memory instead of holding threads that wait for connections.
 * Use [Builder.forbidBlockingWhen] to fail fast when synchronous calls happen on a game thread.
 *
 * Close the database on shutdown to release all connections.
 */
public class Database private constructor(
    private val dataSource: HikariDataSource,
    private val blockingCheck: BooleanSupplier?,
) : SqlSession, AutoCloseable {
    private val executor: ExecutorService = Executors.newFixedThreadPool(dataSource.maximumPoolSize) { runnable ->
        Thread(runnable, "qavlen-db-${threadIds.incrementAndGet()}").apply { isDaemon = true }
    }

    /** The underlying data source, for libraries that need one. */
    public val source: DataSource get() = dataSource

    override fun <T> query(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): List<T> =
        connection { ConnectionSession(it).query(sql, mapper, *parameters) }

    override fun update(sql: String, vararg parameters: Any?): Int = connection { ConnectionSession(it).update(sql, *parameters) }

    override fun insert(sql: String, vararg parameters: Any?): Long? = connection { ConnectionSession(it).insert(sql, *parameters) }

    override fun batch(sql: String, rows: List<List<Any?>>): IntArray = transaction { it.batch(sql, rows) }

    /** Runs [work] in a transaction that is committed when it returns and rolled back when it throws. */
    public fun <T> transaction(work: TransactionWork<T>): T = connection { connection ->
        val autoCommit = connection.autoCommit
        connection.autoCommit = false
        try {
            work.run(ConnectionSession(connection)).also { connection.commit() }
        } catch (exception: Throwable) {
            try {
                connection.rollback()
            } catch (rollback: SQLException) {
                exception.addSuppressed(rollback)
            }
            throw exception
        } finally {
            connection.autoCommit = autoCommit
        }
    }

    public fun <T> queryAsync(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): CompletableFuture<List<T>> =
        async { query(sql, mapper, *parameters) }

    public fun <T> queryFirstAsync(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): CompletableFuture<T?> =
        async { queryFirst(sql, mapper, *parameters) }

    public fun updateAsync(sql: String, vararg parameters: Any?): CompletableFuture<Int> = async { update(sql, *parameters) }

    public fun insertAsync(sql: String, vararg parameters: Any?): CompletableFuture<Long?> = async { insert(sql, *parameters) }

    public fun batchAsync(sql: String, rows: List<List<Any?>>): CompletableFuture<IntArray> = async { batch(sql, rows) }

    public fun <T> transactionAsync(work: TransactionWork<T>): CompletableFuture<T> = async { transaction(work) }

    override fun close() {
        executor.shutdown()
        if (!executor.awaitTermination(10, TimeUnit.SECONDS)) executor.shutdownNow()
        dataSource.close()
    }

    private fun <T> async(block: () -> T): CompletableFuture<T> = CompletableFuture.supplyAsync(block, executor)

    private fun <T> connection(block: (Connection) -> T): T {
        if (blockingCheck?.asBoolean == true) {
            throw IllegalStateException("Blocking database call on ${Thread.currentThread().name}; use the Async methods instead")
        }
        return try {
            dataSource.connection.use(block)
        } catch (exception: SQLException) {
            throw DatabaseException(exception.message ?: "Database error", exception)
        }
    }

    public class Builder internal constructor(private val config: HikariConfig) {
        private var blockingCheck: BooleanSupplier? = null

        public fun credentials(username: String, password: String): Builder = apply {
            config.username = username
            config.password = password
        }

        /** Maximum number of pooled connections, which also bounds concurrent asynchronous queries. */
        public fun poolSize(size: Int): Builder = apply {
            require(size in 1..100) { "Pool size must be between 1 and 100" }
            config.maximumPoolSize = size
        }

        public fun connectionTimeout(timeout: Duration): Builder = apply { config.connectionTimeout = timeout.toMillis() }

        /** Loads the driver class explicitly, which works in plugin class loaders where `DriverManager` cannot see it. */
        public fun driver(className: String): Builder = apply { config.driverClassName = className }

        public fun poolName(name: String): Builder = apply { config.poolName = name }

        /** Adds a driver property, for example `useSSL`. */
        public fun property(name: String, value: String): Builder = apply { config.addDataSourceProperty(name, value) }

        /**
         * Rejects synchronous calls while [check] returns `true`, for example `Bukkit::isPrimaryThread`, so blocking
         * queries cannot freeze a game server by accident.
         */
        public fun forbidBlockingWhen(check: BooleanSupplier): Builder = apply { blockingCheck = check }

        /** Opens the pool. Fails if the database cannot be reached within the connection timeout. */
        public fun connect(): Database = try {
            Database(HikariDataSource(config), blockingCheck)
        } catch (exception: RuntimeException) {
            val cause = exception.cause as? SQLException ?: throw exception
            throw DatabaseException("Could not connect to ${config.jdbcUrl}: ${cause.message}", cause)
        }
    }

    public companion object {
        private val threadIds = AtomicInteger()

        /** A database for any JDBC URL. The matching driver must be on the classpath. */
        @JvmStatic
        public fun builder(jdbcUrl: String): Builder = Builder(
            HikariConfig().apply {
                this.jdbcUrl = jdbcUrl
                maximumPoolSize = 4
                poolName = "qavlen"
            },
        )

        /** MariaDB or MySQL through the MariaDB driver (`org.mariadb.jdbc:mariadb-java-client`). */
        @JvmStatic
        public fun mariadb(host: String, port: Int, database: String): Builder =
            builder("jdbc:mariadb://$host:$port/$database").also { it.driver("org.mariadb.jdbc.Driver") }

        /** PostgreSQL through `org.postgresql:postgresql`. */
        @JvmStatic
        public fun postgresql(host: String, port: Int, database: String): Builder =
            builder("jdbc:postgresql://$host:$port/$database").also { it.driver("org.postgresql.Driver") }
    }
}

internal class ConnectionSession(private val connection: Connection) : SqlSession {
    override fun <T> query(sql: String, mapper: RowMapper<T>, vararg parameters: Any?): List<T> =
        connection.prepareStatement(sql).use { statement ->
            bind(statement, parameters.asList())
            statement.executeQuery().use { result ->
                buildList { while (result.next()) add(mapper.map(result)) }
            }
        }

    override fun update(sql: String, vararg parameters: Any?): Int = connection.prepareStatement(sql).use { statement ->
        bind(statement, parameters.asList())
        statement.executeUpdate()
    }

    override fun insert(sql: String, vararg parameters: Any?): Long? =
        connection.prepareStatement(sql, Statement.RETURN_GENERATED_KEYS).use { statement ->
            bind(statement, parameters.asList())
            statement.executeUpdate()
            statement.generatedKeys.use { keys -> if (keys.next()) keys.getLong(1) else null }
        }

    override fun batch(sql: String, rows: List<List<Any?>>): IntArray = connection.prepareStatement(sql).use { statement ->
        for (row in rows) {
            bind(statement, row)
            statement.addBatch()
        }
        statement.executeBatch()
    }

    private fun bind(statement: PreparedStatement, parameters: List<Any?>) {
        parameters.forEachIndexed { index, value ->
            when (value) {
                is Enum<*> -> statement.setString(index + 1, value.name)
                is Instant -> statement.setTimestamp(index + 1, Timestamp.from(value))
                else -> statement.setObject(index + 1, value)
            }
        }
    }
}
