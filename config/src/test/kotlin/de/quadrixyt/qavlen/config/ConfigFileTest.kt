package de.quadrixyt.qavlen.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigFileTest {
    @TempDir
    lateinit var directory: Path

    private val defaults = ConfigOptions {
        defaults(mapOf("database" to mapOf("host" to "localhost", "port" to 3306), "debug" to false))
    }

    @Test
    fun `creates missing file from defaults`() {
        val path = directory.resolve("config.yml")
        val config = YamlConfig.load(path, defaults)

        assertEquals(3306, config.getInt("database.port"))
        assertTrue(Files.exists(path))
        assertEquals(3306, YamlConfig.load(path).getInt("database.port"))
    }

    @Test
    fun `adds missing defaults without replacing existing values`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  port: 5432\n")

        val config = YamlConfig.load(path, defaults)

        assertEquals(5432, config.getInt("database.port"))
        assertEquals("localhost", config.getString("database.host"))
        assertTrue(path.readText().contains("host: localhost"))
        assertTrue(path.readText().contains("port: 5432"))
    }

    @Test
    fun `does not rewrite unchanged files`() {
        val path = directory.resolve("config.yml")
        path.writeText("# kept\ndatabase: {host: db, port: 1}\ndebug: true\n")

        val config = YamlConfig.load(path, defaults)

        assertEquals("# kept\ndatabase: {host: db, port: 1}\ndebug: true\n", path.readText())
        assertFalse(config.isDirty)
        assertFalse(config.save())
    }

    @Test
    fun `set and save persist nested values`() {
        val path = directory.resolve("config.yml")
        val config = YamlConfig.load(path, defaults)

        config.set("database.port", 3307)
        config.set("features.list", listOf("a", "b"))

        assertTrue(config.isDirty)
        assertTrue(config.save())
        val reloaded = YamlConfig.load(path)
        assertEquals(3307, reloaded.getInt("database.port"))
        assertEquals(listOf("a", "b"), reloaded.getStringList("features.list"))
    }

    @Test
    fun `invalid yaml is reported and never overwritten`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  port: [1\n")

        val error = assertFailsWith<ConfigException> { YamlConfig.load(path, defaults) }

        assertTrue(error.message!!.contains("Invalid YAML"))
        assertEquals("database:\n  port: [1\n", path.readText())
    }

    @Test
    fun `failed reload keeps the last valid values`() {
        val path = directory.resolve("config.yml")
        val config = YamlConfig.load(path, defaults)
        path.writeText("database: [broken")

        assertFailsWith<ConfigException> { config.reload() }

        assertEquals(3306, config.getInt("database.port"))
    }

    @Test
    fun `duplicate keys are rejected`() {
        assertFailsWith<ConfigException> { YamlConfig.parse("a: 1\na: 2\n") }
        assertFailsWith<ConfigException> { JsonConfig.parse("""{"a": 1, "a": 2}""") }
    }

    @Test
    fun `section and value conflicts with defaults are detected`() {
        val path = directory.resolve("config.yml")
        path.writeText("database: mysql\n")

        val error = assertFailsWith<ConfigException> { YamlConfig.load(path, defaults) }

        assertTrue(error.message!!.contains("'database'"))
        assertEquals("database: mysql\n", path.readText())
    }

    @Test
    fun `typed getters reject wrong types`() {
        val config = YamlConfig.parse("port: abc\nratio: 0.5\nbig: 9999999999\n")

        assertFailsWith<ConfigValueException> { config.getInt("port") }
        assertFailsWith<ConfigValueException> { config.getInt("ratio") }
        assertFailsWith<ConfigValueException> { config.getInt("big") }
        assertEquals(9_999_999_999L, config.getLong("big"))
        assertEquals(0.5, config.getDouble("ratio"))
        assertNull(config.getInt("missing"))
        assertEquals(7, config.getInt("missing", 7))
    }

    @Test
    fun `validators block loading and saving`() {
        val options = defaults.toBuilder().validator(ConfigValidator.range("database.port", 1, 65535)).build()
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  port: 70000\n")

        val error = assertFailsWith<ConfigValidationException> { YamlConfig.load(path, options) }
        assertEquals("database.port", error.violations.single().path)

        path.writeText("database:\n  port: 80\n")
        val config = YamlConfig.load(path, options)
        config.set("database.port", 0)
        assertFailsWith<ConfigValidationException> { config.save() }
    }

    @Test
    fun `migrations run in order and create a backup`() {
        val path = directory.resolve("config.yml")
        path.writeText("db-host: example\n")
        val options = ConfigOptions {
            version(3)
            migration(1) { config ->
                config.set("database.host", config.getString("db-host"))
                config.remove("db-host")
            }
            migration(2) { config -> config.set("database.port", 3306) }
        }

        val config = YamlConfig.load(path, options)

        assertEquals("example", config.getString("database.host"))
        assertEquals(3306, config.getInt("database.port"))
        assertEquals(3, config.getInt("config-version"))
        val backup = directory.listDirectoryEntries("config.yml.v1-*.bak").single()
        assertEquals("db-host: example\n", backup.readText())
    }

    @Test
    fun `newer file versions are rejected`() {
        val path = directory.resolve("config.yml")
        path.writeText("config-version: 5\n")

        assertFailsWith<ConfigException> { YamlConfig.load(path, ConfigOptions { version(2).migration(1) {} }) }
    }

    @Test
    fun `json round trip keeps types`() {
        val path = directory.resolve("config.json")
        val config = JsonConfig.load(path, defaults)
        config.set("ratio", 1.5)
        config.save()

        val reloaded = JsonConfig.load(path)
        assertEquals(3306, reloaded.getInt("database.port"))
        assertEquals(1.5, reloaded.getDouble("ratio"))
        assertEquals(false, reloaded.getBoolean("debug"))
        assertFailsWith<ConfigException> { JsonConfig.parse("{\"a\": 1,}") }
    }

    @Test
    fun `sections are live views`() {
        val config = YamlConfig.parse("database:\n  host: a\n  port: 1\n")
        val database = config.getSection("database")!!

        database.set("host", "b")

        assertEquals("b", config.getString("database.host"))
        assertEquals(setOf("host", "port"), database.keys())
        assertEquals(setOf("database.host", "database.port"), config.keys(true))
    }

    @Test
    fun `setting below a value fails`() {
        val config = YamlConfig.parse("database: mysql\n")

        assertFailsWith<ConfigException> { config.set("database.port", 1) }
    }
}
