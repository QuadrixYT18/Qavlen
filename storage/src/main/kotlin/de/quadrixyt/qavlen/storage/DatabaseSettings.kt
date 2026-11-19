package de.quadrixyt.qavlen.storage

import java.time.Duration

/** Database servers with a built-in connection URL. The driver must be on the classpath. */
public enum class DatabaseType(public val defaultPort: Int, internal val scheme: String, internal val driver: String) {
    /** MariaDB or MySQL through `org.mariadb.jdbc:mariadb-java-client`. */
    MARIADB(3306, "mariadb", "org.mariadb.jdbc.Driver"),

    /** PostgreSQL through `org.postgresql:postgresql`. */
    POSTGRESQL(5432, "postgresql", "org.postgresql.Driver"),
}

/**
 * Connection settings, ready to be part of a typed configuration:
 *
 * ```kotlin
 * @Configuration
 * data class PluginConfig(val database: DatabaseSettings = DatabaseSettings(database = "coins"))
 *
 * val database = Database.connect(config.value.database)
 * ```
 *
 * [url] replaces [type], [host], [port] and [database] when set, for example for H2 or special JDBC options.
 * [toString] never prints the password.
 */
public data class DatabaseSettings @JvmOverloads constructor(
    val type: DatabaseType = DatabaseType.MARIADB,
    val host: String = "localhost",
    val port: Int = type.defaultPort,
    val database: String = "minecraft",
    val username: String = "root",
    val password: String = "",
    val poolSize: Int = 4,
    val connectionTimeout: Duration = Duration.ofSeconds(10),
    val queryTimeout: Duration? = null,
    val properties: Map<String, String> = emptyMap(),
    val url: String? = null,
) {
    /** The JDBC URL these settings connect to. A function, so serializers do not write it into configuration files. */
    public fun jdbcUrl(): String = url ?: "jdbc:${type.scheme}://$host:$port/$database"

    override fun toString(): String =
        "DatabaseSettings(url=${jdbcUrl()}, username=$username, password=${if (password.isEmpty()) "" else "***"}, poolSize=$poolSize)"
}
