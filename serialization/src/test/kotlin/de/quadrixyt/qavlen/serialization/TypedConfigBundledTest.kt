package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import org.junit.jupiter.api.io.TempDir
import java.net.URLClassLoader
import java.nio.file.Files
import java.nio.file.Path
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs
import kotlin.test.assertTrue

class TypedConfigBundledTest {
    data class Settings(val prefix: String = "class prefix", val maxPlayers: Int = 10, val motd: String = "class motd")

    @TempDir
    lateinit var directory: Path

    private val resource = "# Shown before messages\nprefix: \"<gray>[Res]\"\n\n# Player limit\nmax-players: 20\nmotd: Welcome\n"

    private fun resources(vararg files: Pair<String, String>): URLClassLoader {
        val root = Files.createTempDirectory(directory, "resources")
        files.forEach { (name, text) -> root.resolve(name).writeText(text) }
        return URLClassLoader(arrayOf(root.toUri().toURL()), null)
    }

    @Test
    fun `the first start copies the bundled file`() {
        val file = directory.resolve("settings.yml")

        val config = resources("settings.yml" to resource).use { loader ->
            Configs.open<Settings>(file) { bundled(loader) }
        }

        assertEquals(resource, file.readText())
        assertEquals(Settings("<gray>[Res]", 20, "Welcome"), config.value)
    }

    @Test
    fun `missing keys come from the bundled file before the class defaults`() {
        val file = directory.resolve("settings.yml")
        file.writeText("prefix: \"<blue>Mine\"\n")

        val config = resources("settings.yml" to "# Player limit\nmax-players: 20\n").use { loader ->
            Configs.open<Settings>(file) { bundled(loader) }
        }
        val text = file.readText()

        assertEquals(Settings("<blue>Mine", 20, "class motd"), config.value)
        assertTrue("prefix: \"<blue>Mine\"" in text, text)
        assertTrue("# Player limit\nmax-players: 20" in text, text)
    }

    @Test
    fun `a missing resource fails before anything is written`() {
        val file = directory.resolve("settings.yml")

        assertFailsWith<ConfigException> { resources().use { loader -> Configs.open<Settings>(file) { bundled(loader) } } }
        assertTrue(Files.notExists(file))
    }

    @Test
    fun `conflicts of typed configurations are configuration conflicts`() {
        val file = directory.resolve("settings.yml")
        val config = Configs.open<Settings>(file)
        file.writeText(file.readText() + "# edited\n")

        val conflict = assertFailsWith<ConfigConflictException> { config.update { copy(maxPlayers = 5) }.also { config.save() } }

        assertIs<de.quadrixyt.qavlen.config.ConfigConflictException>(conflict)
    }
}
