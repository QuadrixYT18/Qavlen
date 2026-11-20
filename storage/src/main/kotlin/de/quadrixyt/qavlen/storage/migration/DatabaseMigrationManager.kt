package de.quadrixyt.qavlen.storage.migration

import de.quadrixyt.qavlen.storage.DatabaseSettings
import de.quadrixyt.qavlen.storage.dialect.dialect
import java.nio.charset.StandardCharsets
import java.security.MessageDigest
import java.sql.Connection

/**
 * Moves the data to a new database when the configured database changes, for example from SQLite to MariaDB.
 *
 * [stateStore] records which database is active. Register it once with [initializeActive]; afterwards [prepare]
 * copies the old database into the empty new one when the settings point elsewhere. The old database stays
 * authoritative until the copy has been validated.
 */
public class DatabaseMigrationManager(
    private val stateStore: DatabaseMigrationStateStore,
    private val transfer: DatabaseTransfer = DatabaseTransfer(),
) {
    /**
     * Copies the old database into the new one if [newConfig] is not the recorded active database.
     *
     * @throws IllegalStateException when no state is recorded, an earlier migration did not finish, or the copy fails
     */
    public fun prepare(
        newConfig: DatabaseSettings,
        oldConnection: (() -> Connection)?,
        newConnection: () -> Connection,
        oldConfig: DatabaseSettings? = null,
        batchSize: Int = 500,
    ) {
        val last = stateStore.read()
            ?: error("No migration state found. Register the currently active database explicitly before enabling automatic switching.")
        check(last.status == DatabaseMigrationState.Status.ACTIVE) {
            "Unfinished database migration detected. Inspect target and recover manually before retrying."
        }
        if (last.activeIdentity == identity(newConfig)) return
        require(oldConfig != null && oldConnection != null) { "Old database connection settings are required for migration" }
        require(identity(oldConfig) == last.activeIdentity) { "Old database configuration does not match recorded active database" }
        stateStore.write(last.copy(status = DatabaseMigrationState.Status.IN_PROGRESS))
        try {
            oldConnection().use { source ->
                newConnection().use { target ->
                    transfer.transfer(source, target, dialect(oldConfig.type), dialect(newConfig.type), batchSize)
                }
            }
            stateStore.write(DatabaseMigrationState(identity(newConfig)))
        } catch (e: Exception) {
            throw IllegalStateException(
                "Database migration aborted. Old database remains authoritative; inspect target before retrying.",
                e,
            )
        }
    }

    /** Records [config] as the active database. Fails if a state already exists. */
    public fun initializeActive(config: DatabaseSettings) {
        check(stateStore.read() == null) { "Migration state already exists" }
        stateStore.write(DatabaseMigrationState(identity(config)))
    }

    private fun identity(settings: DatabaseSettings): String {
        val location = settings.jdbcUrl().substringBefore('?') + "|" + settings.username
        val digest = MessageDigest.getInstance("SHA-256")
            .digest(location.toByteArray(StandardCharsets.UTF_8))
            .joinToString("") { "%02x".format(it) }
        return "${settings.type.name}:$digest"
    }
}
