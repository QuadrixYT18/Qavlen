package de.quadrixyt.qavlen.config

import org.junit.jupiter.api.io.TempDir
import java.math.BigDecimal
import java.math.BigInteger
import java.nio.file.Path
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigRegressionTest {
    @TempDir
    lateinit var directory: Path

    private val migrateToTwo = ConfigOptions {
        version(2)
        migration(1) { it.set("renamed", it.get("old")) }
        writeDefaults(false)
    }

    @Test
    fun `migrations are saved even when defaults are not written`() {
        val path = directory.resolve("config.yml")
        path.writeText("old: value\n")

        YamlConfig.load(path, migrateToTwo)

        assertTrue("config-version: 2" in path.readText(), path.readText())
        assertTrue("renamed: value" in path.readText(), path.readText())
    }

    @Test
    fun `backups never overwrite each other`() {
        val path = directory.resolve("config.yml")
        repeat(3) {
            path.writeText("old: run$it\n")
            YamlConfig.load(path, migrateToTwo)
        }

        val backups = directory.listDirectoryEntries("config.yml.v1-*.bak")
        assertEquals(3, backups.size, backups.toString())
        assertEquals(setOf("old: run0\n", "old: run1\n", "old: run2\n"), backups.map { it.readText() }.toSet())
    }

    @Test
    fun `failed migrations keep the file and the active values`() {
        val path = directory.resolve("config.yml")
        path.writeText("old: value\n")
        val failing = ConfigOptions {
            version(2)
            migration(1) { error("broken migration") }
        }

        assertFailsWith<ConfigException> { YamlConfig.load(path, failing) }

        assertEquals("old: value\n", path.readText())
        assertEquals(emptyList(), directory.listDirectoryEntries("*.bak"))
    }

    @Test
    fun `large integers stay numbers in yaml`() {
        val path = directory.resolve("config.yml")
        path.writeText("id: 123456789012345678901234567890\n")

        val config = YamlConfig.load(path)
        config.set("other", 1)
        config.save()

        assertEquals(BigInteger("123456789012345678901234567890"), config.get("id"))
        assertTrue("id: 123456789012345678901234567890\n" in path.readText(), path.readText())
        assertFailsWith<ConfigValueException> { config.getLong("id") }
    }

    @Test
    fun `json numbers keep their precision`() {
        val path = directory.resolve("config.json")
        path.writeText("""{"big": 12345678901234567890, "precise": 1.23456789012345678901, "plain": 0.5}""")

        val config = JsonConfig.load(path)
        config.set("other", 1)
        config.save()

        assertEquals(BigInteger("12345678901234567890"), config.get("big"))
        assertEquals(BigDecimal("1.23456789012345678901"), config.get("precise"))
        assertEquals(0.5, config.get("plain"))
        assertTrue("\"big\": 12345678901234567890" in path.readText(), path.readText())
        assertTrue("\"precise\": 1.23456789012345678901" in path.readText(), path.readText())
    }

    @Test
    fun `explicit null differs from a missing key and survives default merging`() {
        val config = YamlConfig.parse("value: null\n", ConfigOptions { defaults(mapOf("value" to "default", "missing" to "default")) })

        assertTrue(config.contains("value"))
        assertNull(config.getString("value"))
        assertEquals("fallback", config.getString("value", "fallback"))
        assertEquals("default", config.getString("missing"))
    }
}
