package de.quadrixyt.qavlen.storage

import java.util.zip.CRC32

/** One schema change. Statements run in order inside a transaction. */
public class Migration(public val version: Int, public val description: String, public val statements: List<String>) {
    init {
        require(version >= 1) { "Migration versions start at 1" }
        require(statements.isNotEmpty()) { "Migration $version has no statements" }
    }

    internal val checksum: Long = CRC32().apply { statements.forEach { update(it.trim().toByteArray()) } }.value
}

/**
 * Versioned schema migrations recorded in a history table.
 *
 * Each pending migration runs in its own transaction, in version order. Applied migrations are verified by checksum,
 * so a migration that was edited after it ran is reported instead of silently diverging. PostgreSQL rolls back
 * failed DDL; MariaDB commits DDL implicitly, so keep each MariaDB migration to statements that are safe to retry.
 */
public class Migrations @JvmOverloads constructor(
    private val database: Database,
    private val table: String = "qavlen_schema_history",
) {
    private val migrations = sortedMapOf<Int, Migration>()

    init {
        require(Regex("[A-Za-z_][A-Za-z0-9_]{0,62}").matches(table)) { "Invalid history table name '$table'" }
    }

    public fun add(version: Int, description: String, vararg statements: String): Migrations = add(
        Migration(version, description, statements.toList()),
    )

    public fun add(migration: Migration): Migrations = apply {
        require(migrations.putIfAbsent(migration.version, migration) == null) { "Migration ${migration.version} is defined twice" }
    }

    /**
     * Applies pending migrations and returns their versions.
     *
     * @throws IllegalStateException when an applied migration was changed or is unknown to this code
     */
    public fun migrate(): List<Int> {
        database.update(
            "CREATE TABLE IF NOT EXISTS $table (version INT PRIMARY KEY, description VARCHAR(200) NOT NULL, " +
                "checksum BIGINT NOT NULL, applied_at TIMESTAMP NOT NULL)",
        )
        val applied = database.query("SELECT version, checksum FROM $table", { it.getInt(1) to it.getLong(2) }).toMap()
        applied.forEach { (version, checksum) ->
            val migration =
                migrations[version] ?: throw IllegalStateException("Database has migration $version, which this version does not know")
            check(migration.checksum == checksum) { "Migration $version '${migration.description}' was changed after it was applied" }
        }
        return migrations.values.filter { it.version !in applied }.map { migration ->
            database.transaction { session ->
                migration.statements.forEach { session.update(it) }
                session.update(
                    "INSERT INTO $table (version, description, checksum, applied_at) VALUES (?, ?, ?, CURRENT_TIMESTAMP)",
                    migration.version,
                    migration.description.take(200),
                    migration.checksum,
                )
            }
            migration.version
        }
    }
}
