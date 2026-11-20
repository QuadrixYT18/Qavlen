package de.quadrixyt.qavlen.storage.migration

import java.nio.charset.StandardCharsets
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.util.Properties

/** The active database, identified by a hash of its URL and user, and whether a migration is running. */
public data class DatabaseMigrationState(public val activeIdentity: String, public val status: Status = Status.ACTIVE) {
    public enum class Status { ACTIVE, IN_PROGRESS }
}

/** Stores the [DatabaseMigrationState] in a properties file, written atomically. The file contains no credentials. */
public class DatabaseMigrationStateStore(private val file: Path) {
    /** The recorded state, or `null` if none was written yet. */
    public fun read(): DatabaseMigrationState? {
        if (!Files.exists(file)) return null
        val properties = Properties()
        Files.newBufferedReader(file, StandardCharsets.UTF_8).use { properties.load(it) }
        val identity = properties.getProperty("activeIdentity")?.takeIf { it.isNotBlank() }
            ?: error("Invalid migration state: missing activeIdentity")
        val status = DatabaseMigrationState.Status.valueOf(properties.getProperty("status", "ACTIVE"))
        return DatabaseMigrationState(identity, status)
    }

    public fun write(state: DatabaseMigrationState) {
        val parent = file.toAbsolutePath().parent
        Files.createDirectories(parent)
        val properties = Properties().apply {
            setProperty("activeIdentity", state.activeIdentity)
            setProperty("status", state.status.name)
        }
        val tmp = Files.createTempFile(parent, ".qavlen-storage-", ".tmp")
        try {
            Files.newBufferedWriter(tmp, StandardCharsets.UTF_8).use { properties.store(it, "Qavlen storage state; no secrets") }
            try {
                Files.move(tmp, file, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            } catch (_: AtomicMoveNotSupportedException) {
                Files.move(tmp, file, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally {
            Files.deleteIfExists(tmp)
        }
    }
}
