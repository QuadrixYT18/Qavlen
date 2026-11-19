package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.ConfigValidationException
import de.quadrixyt.qavlen.config.ConfigViolation
import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Duration
import java.time.Instant
import java.util.UUID
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

enum class Difficulty { EASY, HARD }

@Configuration("Example plugin configuration")
data class PluginConfig(
    @Comment("Maximum player count")
    @Range(min = 1.0, max = 100.0)
    val maxPlayers: Int = 20,
    @Comment("Database connection")
    val database: DatabaseConfig = DatabaseConfig(),
    val messages: MessagesConfig = MessagesConfig(),
    val difficulty: Difficulty = Difficulty.EASY,
    val worlds: List<String> = listOf("world"),
    val rewards: Map<String, Int> = mapOf("daily" to 10),
    val motd: String? = null,
)

data class DatabaseConfig(
    @Comment("Database hostname")
    @NotBlank
    val host: String = "localhost",
    @Comment("Database port")
    @Range(min = 1.0, max = 65535.0)
    val port: Int = 3306,
    val timeout: Duration = Duration.ofSeconds(10),
)

data class MessagesConfig(val welcome: String = "<green>Welcome!")

data class Everything(
    val id: UUID = UUID.fromString("7c9e6679-7425-40de-944b-e07fc1f90ae7"),
    val price: BigDecimal = BigDecimal("1.23456789012345678901"),
    val since: Instant = Instant.parse("2025-01-01T00:00:00Z"),
    val tags: Set<String> = setOf("a"),
    val numbers: IntArray = intArrayOf(1, 2),
    @Length(min = 1, max = 5) @Pattern("[a-z]+") val code: String = "abc",
) {
    override fun equals(other: Any?) = other is Everything &&
        id == other.id &&
        price == other.price &&
        since == other.since &&
        tags == other.tags &&
        numbers.contentEquals(other.numbers) &&
        code == other.code

    override fun hashCode() = id.hashCode()
}

class TypedConfigTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `creates the file from data class defaults with comments`() {
        val path = directory.resolve("config.yml")

        val config = Configs.open<PluginConfig>(path)

        assertEquals(PluginConfig(), config.value)
        val text = path.readText()
        assertTrue(text.startsWith("# Example plugin configuration\n\n# Maximum player count\nmax-players: 20\n"), text)
        assertTrue("  # Database hostname\n  host: localhost\n\n  # Database port\n  port: 3306\n" in text, text)
        assertTrue("timeout: 10s" in text, text)
        assertEquals(PluginConfig(), Configs.open<PluginConfig>(path).value)
    }

    @Test
    fun `fills missing values and keeps user values and unknown keys`() {
        val path = directory.resolve("config.yml")
        path.writeText("max-players: 50\ndatabase:\n  host: db.internal\nlegacy-option: true\n")

        val config = Configs.open<PluginConfig>(path)

        assertEquals(PluginConfig(maxPlayers = 50, database = DatabaseConfig(host = "db.internal")), config.value)
        assertEquals(setOf("legacy-option"), config.unknownKeys)
        val text = path.readText()
        assertTrue("max-players: 50" in text && "host: db.internal" in text && "port: 3306" in text && "legacy-option: true" in text, text)
    }

    @Test
    fun `unknown keys can be removed`() {
        val path = directory.resolve("config.yml")
        path.writeText("max-players: 5\nold: 1\n")

        Configs.open<PluginConfig>(path) { unknownKeys(UnknownKeys.REMOVE) }

        assertFalse("old:" in path.readText())
    }

    @Test
    fun `updates are validated and saved explicitly`() {
        val path = directory.resolve("config.yml")
        val config = Configs.open<PluginConfig>(path)
        val seen = mutableListOf<PluginConfig>()
        config.addListener(seen::add)

        config.update { copy(maxPlayers = 50) }
        assertTrue("max-players: 20" in path.readText())
        assertTrue(config.save())
        assertFalse(config.save())

        assertTrue("max-players: 50" in path.readText())
        assertEquals(50, Configs.open<PluginConfig>(path).value.maxPlayers)
        val error = assertFailsWith<ConfigValidationException> { config.update { copy(maxPlayers = 500) } }
        assertEquals(listOf("max-players: expected value between 1 and 100, got 500"), error.violations.map(ConfigViolation::toString))
        assertEquals(50, config.value.maxPlayers)
        assertEquals(listOf(50), seen.map { it.maxPlayers })
    }

    @Test
    fun `reports every invalid value with its path`() {
        val path = directory.resolve("config.yml")
        path.writeText("max-players: 0\ndatabase:\n  host: ' '\n  port: 70000\n")

        val error = assertFailsWith<ConfigValidationException> { Configs.open<PluginConfig>(path) }

        assertEquals(
            listOf(
                "max-players: expected value between 1 and 100, got 0",
                "database.host: must not be blank",
                "database.port: expected value between 1 and 65535, got 70000",
            ),
            error.violations.map(ConfigViolation::toString),
        )
        assertEquals("max-players: 0\ndatabase:\n  host: ' '\n  port: 70000\n", path.readText())
    }

    @Test
    fun `wrong types name the file and path`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  port: many\n")

        val error = assertFailsWith<ConfigException> { Configs.open<PluginConfig>(path) }

        assertTrue("config.yml" in error.message!! && "'database.port'" in error.message!!, error.message)
    }

    @Test
    fun `failed reloads keep the previous value and the file`() {
        val path = directory.resolve("config.yml")
        val config = Configs.open<PluginConfig>(path)
        path.writeText("max-players: [broken")

        assertFailsWith<ConfigException> { config.reload() }

        assertEquals(20, config.value.maxPlayers)
        assertEquals("max-players: [broken", path.readText())
    }

    @Test
    fun `saving does not overwrite external changes`() {
        val path = directory.resolve("config.yml")
        val config = Configs.open<PluginConfig>(path)
        config.update { copy(maxPlayers = 30) }
        path.writeText(path.readText().replace("max-players: 20", "max-players: 40"))
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().plusSeconds(5)))

        assertFailsWith<ConfigConflictException> { config.save() }
        assertTrue(config.reloadIfModified())
        assertEquals(40, config.value.maxPlayers)
        assertFalse(config.reloadIfModified())
        config.update { copy(maxPlayers = 41) }
        assertTrue(config.save())
    }

    @Test
    fun `overrides win but are never written`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  port: 3306\n")
        val config = Configs.open<PluginConfig>(path) {
            environment("APP", mapOf("APP_DATABASE_PORT" to "5432", "APP_MAX_PLAYERS" to "7"))
            systemProperties("app", mapOf("app.database.host" to "props-host"))
        }

        assertEquals(5432, config.value.database.port)
        assertEquals(7, config.value.maxPlayers)
        assertEquals("props-host", config.value.database.host)
        config.update { copy(difficulty = Difficulty.HARD) }
        config.save()

        val text = path.readText()
        assertTrue("port: 3306" in text && "max-players: 20" in text && "host: localhost" in text && "difficulty: HARD" in text, text)
    }

    @Test
    fun `binds a section and keeps the rest of the file`() {
        val path = directory.resolve("config.yml")
        path.writeText("other: kept\ndatabase:\n  host: db\n")

        val config = Configs.open<DatabaseConfig>(path) { prefix("database") }
        config.update { copy(port = 1234) }
        config.save()

        assertEquals(DatabaseConfig(host = "db", port = 1234), Configs.open<DatabaseConfig>(path) { prefix("database") }.value)
        assertTrue("other: kept" in path.readText())
    }

    @Test
    fun `migrations run before decoding and create backups`() {
        val path = directory.resolve("config.yml")
        path.writeText("max-players: 5\ndb-host: legacy\n")

        val config = Configs.open<PluginConfig>(path) {
            version(2)
            migration(1) { section ->
                section.set("database.host", section.get("db-host"))
                section.remove("db-host")
            }
            writeDefaults(false)
        }

        assertEquals("legacy", config.value.database.host)
        assertTrue("config-version: 2" in path.readText())
        assertEquals(1, directory.listDirectoryEntries("config.yml.v1-*.bak").size)
    }

    @Test
    fun `json and toml keep the same object api`() {
        val json = Configs.open<PluginConfig>(directory.resolve("config.json"))
        json.update { copy(maxPlayers = 9, motd = "hi") }
        json.save()
        assertEquals(json.value, Configs.open<PluginConfig>(directory.resolve("config.json")).value)

        val tomlPath = directory.resolve("config.toml")
        val toml = Configs.open<PluginConfig>(tomlPath)
        toml.update { copy(maxPlayers = 11, worlds = listOf("a", "b")) }
        toml.save()
        assertTrue("max-players = 11" in tomlPath.readText(), tomlPath.readText())
        assertEquals(toml.value, Configs.open<PluginConfig>(tomlPath).value)
        assertNull(toml.value.motd)
    }

    @Test
    fun `in-memory configurations and special types`() {
        val config = Configs.parse<Everything>("code: xyz\n", ConfigFileFormat.YAML)
        assertEquals(Everything(code = "xyz"), config.value)
        assertEquals(Everything(), Configs.parse<Everything>(Configs.encode(Everything()), ConfigFileFormat.YAML).value)
        assertFailsWith<ConfigException> { config.save() }

        val invalid = assertFailsWith<ConfigValidationException> { Configs.parse<Everything>("code: TOOLONG1\n", ConfigFileFormat.YAML) }
        assertEquals(
            listOf("code: expected length between 1 and 5, got 8"),
            invalid.violations.map(ConfigViolation::toString),
        )
    }

    @Test
    fun `custom validators run with annotations`() {
        val error = assertFailsWith<ConfigValidationException> {
            Configs.parse<DatabaseConfig>("port: 80\n", ConfigFileFormat.YAML) {
                validator { value -> if (value.port < 1024) listOf(ConfigViolation("port", "must not be a privileged port")) else emptyList() }
            }
        }

        assertEquals(listOf("port: must not be a privileged port"), error.violations.map(ConfigViolation::toString))
    }
}
