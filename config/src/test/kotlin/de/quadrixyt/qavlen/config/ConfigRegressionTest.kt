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
import kotlin.test.assertFalse
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

    @Test
    fun `yaml dates stay unchanged text`() {
        val path = directory.resolve("config.yml")
        path.writeText("event: 2025-01-01\nupdated: 2025-01-01 10:00:00\n")

        val config = YamlConfig.load(path)
        config.set("other", 1)
        config.save()

        assertEquals("2025-01-01", config.getString("event"))
        assertTrue("event: 2025-01-01\nupdated: 2025-01-01 10:00:00\n" in path.readText(), path.readText())
    }

    @Test
    fun `yaml scalars keep their types`() {
        val config = YamlConfig.parse("flag: true\nyes-word: yes\ncount: 0x1F\nratio: 1.5e3\nnothing: ~\nempty:\nbase: &base {a: 1}\nmerged:\n  <<: *base\n  b: 2\n")

        assertEquals(true, config.get("flag"))
        assertEquals(true, config.get("yes-word"))
        assertEquals(31, config.get("count"))
        assertEquals(1500.0, config.get("ratio"))
        assertTrue(config.contains("nothing") && config.get("nothing") == null)
        assertTrue(config.contains("empty") && config.get("empty") == null)
        assertEquals(mapOf("a" to 1, "b" to 2), config.getMap("merged"))
    }

    @Test
    fun `yaml decimals keep their precision`() {
        val config = YamlConfig.parse("precise: 1.23456789012345678901\nplain: 0.5\nlarge: 1.5e300\ninfinite: .inf\n")

        assertEquals(BigDecimal("1.23456789012345678901"), config.get("precise"))
        assertEquals(0.5, config.get("plain"))
        assertEquals(1.5e300, config.get("large"))
        assertEquals(Double.POSITIVE_INFINITY, config.get("infinite"))
        assertTrue("precise: 1.23456789012345678901" in config.saveToString(), config.saveToString())
    }

    @Test
    fun `saving keeps manual yaml comments`() {
        val path = directory.resolve("config.yml")
        val original = """
            # Server settings, edited by hand
            # Second header line

            server:
              # The name shown in the list
              name: Lobby # do not rename during events

              port: 25565
            # Players allowed
            worlds:
              - world # main
              - nether
            removed: value
            # end of file
        """.trimIndent() + "\n"
        path.writeText(original)
        val config = YamlConfig.load(path, ConfigOptions { defaults(mapOf("server" to mapOf("motd" to "Welcome"))) })

        config.set("server.port", 25566)
        config.remove("removed")
        config.save()

        val text = path.readText()
        listOf(
            "# Server settings, edited by hand\n# Second header line\n\nserver:",
            "  # The name shown in the list\n  name: Lobby # do not rename during events",
            "do not rename during events\n\n  port: 25566",
            "motd: Welcome",
            "# Players allowed\nworlds:",
            "- world # main",
            "# end of file",
        ).forEach { assertTrue(it in text, "Missing '$it' in:\n$text") }
        assertFalse("removed" in text, text)
        assertEquals(25566, YamlConfig.load(path).getInt("server.port"))
    }
}
