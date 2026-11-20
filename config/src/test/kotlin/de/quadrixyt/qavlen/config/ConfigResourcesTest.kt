package de.quadrixyt.qavlen.config

import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.createDirectories
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class ConfigResourcesTest {
    @TempDir
    lateinit var directory: Path

    private val v1 =
        """
        # Messages of the plugin

        # Shown before every message
        prefix: "<gray>[Server]"

        welcome: "<green>Welcome!"   # greeting
        """.trimIndent() + "\n"

    /** A class loader that serves [files] as resources, like a plugin jar. */
    private fun resources(vararg files: Pair<String, String>): URLClassLoader {
        val root = Files.createTempDirectory(directory, "resources")
        files.forEach { (name, text) -> root.resolve(name).writeText(text) }
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }

    @Test
    fun `first start creates an exact copy of the resource`() {
        val file = directory.resolve("plugins/demo/messages.yml")

        val config = resources("messages.yml" to v1).use { YamlConfig.load(file, it) }

        assertEquals(v1, file.readText())
        assertEquals("<gray>[Server]", config.getString("prefix"))
    }

    @Test
    fun `an unchanged file is not written again`() {
        val file = directory.resolve("messages.yml")
        resources("messages.yml" to v1).use { YamlConfig.load(file, it) }
        Files.setLastModifiedTime(file, FileTime.fromMillis(1_000_000))

        resources("messages.yml" to v1).use { YamlConfig.load(file, it) }

        assertEquals(1_000_000, Files.getLastModifiedTime(file).toMillis())
    }

    @Test
    fun `new resource keys are added with their comments without touching existing values`() {
        val file = directory.resolve("messages.yml")
        file.writeText(
            """
            # Messages of the plugin

            prefix: "<blue>[MyServer]"

            welcome: '<yellow>Hello!'   # my greeting
            custom: kept
            """.trimIndent() + "\n",
        )
        val v2 = v1 + "\n# Shown without permission\nno-permission: \"<red>No permission!\"\nshop:\n  # Menu title\n  title: Shop\n"

        val config = resources("messages.yml" to v2).use { YamlConfig.load(file, it) }
        val text = file.readText()

        assertEquals("<blue>[MyServer]", config.getString("prefix"))
        assertEquals("kept", config.getString("custom"))
        assertEquals("Shop", config.getString("shop.title"))
        assertTrue("prefix: \"<blue>[MyServer]\"" in text, text)
        assertTrue("welcome: '<yellow>Hello!' # my greeting" in text, text)
        assertTrue("# Shown without permission\nno-permission: \"<red>No permission!\"" in text, text)
        assertTrue("  # Menu title\n  title: Shop" in text, text)
        assertFalse("# Shown before every message" in text, "a removed comment must not come back")
    }

    @Test
    fun `missing and conflicting resources fail without writing`() {
        val missing = directory.resolve("other.yml")
        val failure = assertFailsWith<ConfigException> { resources().use { YamlConfig.load(missing, it) } }
        assertTrue("other.yml" in failure.message!!)
        assertFalse(Files.exists(missing))

        val file = directory.resolve("shop.yml")
        file.writeText("shop: not a section\n")
        assertFailsWith<ConfigException> { resources("shop.yml" to "shop:\n  title: Shop\n").use { YamlConfig.load(file, it) } }
        assertEquals("shop: not a section\n", file.readText())
    }

    @Test
    fun `JSON files are created from resources`() {
        val file = directory.resolve("data.json")

        resources("data.json" to "{\n  \"a\": 1\n}\n").use { JsonConfig.load(file, it) }

        assertEquals("{\n  \"a\": 1\n}\n", file.readText())
    }

    @Test
    fun `quoted values keep their quotes when the file is rewritten`() {
        val file = directory.resolve("config.yml")
        file.writeText("double: \"text\"\nsingle: 'text'\nplain: text\nblock: |\n  line one\n  line two\n\"quoted-key\": 1\n")
        val config = YamlConfig.load(file)

        config.set("double", "changed")
        config.set("added", "new")
        config.save()
        val text = file.readText()

        assertTrue("double: \"changed\"" in text, text)
        assertTrue("single: 'text'" in text, text)
        assertTrue("plain: text" in text, text)
        assertTrue("block: |\n  line one\n  line two" in text, text)
        assertTrue("\"quoted-key\": 1" in text, text)
        assertTrue("added: new" in text, text)
        assertEquals("line one\nline two\n", YamlConfig.load(file).getString("block"))
    }

    @Test
    fun `save refuses to overwrite edits made on disk`() {
        val file = directory.resolve("config.yml")
        file.writeText("a: 1\n")
        val config = YamlConfig.load(file)
        file.writeText("a: 1\nb: edited by hand\n")
        config.set("a", 2)

        assertFailsWith<ConfigConflictException> { config.save() }
        assertEquals("a: 1\nb: edited by hand\n", file.readText())

        assertTrue(config.save(true))
        assertEquals(2, YamlConfig.load(file).getInt("a"))
    }

    @Test
    fun `save works after reload, after its own saves and when the file was deleted`() {
        val file = directory.resolve("config.yml")
        file.writeText("a: 1\n")
        val config = YamlConfig.load(file)
        file.writeText("a: 5\n")

        config.reload()
        config.set("a", 6)
        config.save()
        config.set("a", 7)
        config.save()
        assertEquals(7, YamlConfig.load(file).getInt("a"))

        Files.delete(file)
        config.set("a", 8)
        config.save()
        assertEquals(8, YamlConfig.load(file).getInt("a"))
    }

    @Test
    fun `missing parent directories are created`() {
        val file = directory.resolve("a/b/c.yml")
        directory.resolve("a").createDirectories()

        resources("c.yml" to "x: 1\n").use { YamlConfig.load(file, it) }

        assertEquals("x: 1\n", file.readText())
    }
}
