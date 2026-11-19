package de.quadrixyt.qavlen.config

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.time.Instant
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigSchemaAndListenerTest {
    @TempDir
    lateinit var directory: Path

    private val schema = ConfigSchema.builder()
        .required("database.host", ConfigType.STRING)
        .optional("database.port", ConfigType.INTEGER).range("database.port", 1, 65535)
        .optional("mode", ConfigType.STRING).oneOf("mode", "fast", "safe")
        .optional("worlds", ConfigType.LIST)
        .optional("rewards", ConfigType.SECTION)
        .rejectUnknownKeys()
        .build()

    @Test
    fun `schemas report every violation`() {
        val error = assertFailsWith<ConfigValidationException> {
            YamlConfig.parse(
                "database:\n  port: 70000\n  hots: typo\nmode: slow\nworlds: [a]\nrewards:\n  daily: 5\n",
                ConfigOptions { schema(schema) },
            )
        }

        assertEquals(
            listOf(
                "database.host: is required",
                "database.port: must be between 1 and 65535",
                "mode: must be one of fast, safe",
                "database.hots: is not a known setting",
            ),
            error.violations.map(ConfigViolation::toString),
        )
    }

    @Test
    fun `schemas accept valid documents and the migration version key`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  host: db\n  port: '3306'\n")

        assertFailsWith<ConfigValidationException> { YamlConfig.load(path, ConfigOptions { schema(schema) }) }

        path.writeText("database:\n  host: db\n  port: 3306\n")
        val config = YamlConfig.load(path, ConfigOptions { schema(schema).version(2).migration(1) {} })
        assertEquals(2, config.getInt("config-version"))
    }

    @Test
    fun `listeners see sets, removals and reload differences`() {
        val path = directory.resolve("config.yml")
        path.writeText("database:\n  host: a\n  port: 1\nmode: fast\n")
        val config = YamlConfig.load(path)
        val changes = mutableListOf<ConfigChange>()
        val subscription = config.addListener(changes::add)

        config.set("mode", "fast")
        config.getSection("database")!!.set("port", 2)
        config.remove("mode")
        path.writeText("database:\n  host: b\n  port: 2\nextra: 1\n")
        config.reload()
        subscription.close()
        config.set("mode", "safe")

        assertEquals(
            listOf(setOf("database.port") to false, setOf("mode") to false, setOf("database.host", "extra") to true),
            changes.map { it.paths to it.reload },
        )
        assertTrue(changes.last().affects("database"))
        assertFalse(changes.last().affects("mode"))
    }

    @Test
    fun `failed reloads and failing listeners do not break the configuration`() {
        val path = directory.resolve("config.yml")
        path.writeText("value: 1\n")
        val config = YamlConfig.load(path)
        val seen = mutableListOf<Set<String>>()
        config.addListener { throw IllegalStateException("listener bug") }
        config.addListener { seen += it.paths }

        path.writeText("value: [broken")
        assertFailsWith<ConfigException> { config.reload() }
        config.set("value", 2)

        assertEquals(listOf(setOf("value")), seen)
        assertEquals(2, config.getInt("value"))
    }

    @Test
    fun `reloads only when the file changed`() {
        val path = directory.resolve("config.yml")
        path.writeText("value: 1\n")
        val config = YamlConfig.load(path)

        assertFalse(config.reloadIfModified())
        path.writeText("value: 22\n")
        Files.setLastModifiedTime(path, FileTime.from(Instant.now().plusSeconds(5)))

        assertTrue(config.reloadIfModified())
        assertEquals(22, config.getInt("value"))
        assertFalse(config.reloadIfModified())
        config.set("value", 3)
        config.save()
        assertFalse(config.reloadIfModified())
    }
}
