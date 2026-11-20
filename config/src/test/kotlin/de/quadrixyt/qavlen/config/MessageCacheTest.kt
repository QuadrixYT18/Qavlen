package de.quadrixyt.qavlen.config

import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotSame
import kotlin.test.assertSame

class MessageCacheTest {
    @TempDir
    lateinit var directory: Path

    private val plain = PlainTextComponentSerializer.plainText()

    private fun load(text: String): Pair<Path, YamlConfig> {
        val file = directory.resolve("messages.yml")
        file.writeText(text)
        return file to YamlConfig.load(file)
    }

    /** Writes [text] as a later version of [file] and waits until the cache checks the file again. */
    private fun edit(file: Path, text: String) {
        val previous = Files.getLastModifiedTime(file).toMillis()
        file.writeText(text)
        Files.setLastModifiedTime(file, FileTime.fromMillis(previous + 5_000))
        Thread.sleep(MessageCache.WATCH_INTERVAL_MILLIS + 200)
    }

    @Test
    fun `messages without placeholders are rendered once`() {
        val (_, config) = load("prefix: \"[P]\"\nstatic: \"<prefix> <red>Static\"\nplayer: \"Hi {name}\"\n")

        assertSame(config.getComponent("static"), config.getComponent("static"))
        assertEquals("Hi A", plain.serialize(config.getComponent("player", "name" to "A")))
        assertEquals("Hi B", plain.serialize(config.getComponent("player", "name" to "B")))
    }

    @Test
    fun `changes in memory invalidate cached messages`() {
        val (_, config) = load("prefix: \"[P]\"\nmsg: \"<prefix> one\"\n")
        val first = config.getComponent("msg")

        config.set("prefix", "[Q]")

        assertNotSame(first, config.getComponent("msg"))
        assertEquals("[Q] one", plain.serialize(config.getComponent("msg")))
        config.set("msg", "two")
        assertEquals("two", plain.serialize(config.getComponent("msg")))
    }

    @Test
    fun `an edited file is reloaded on the next lookup`() {
        val (file, config) = load("messages:\n  join: \"old\"\n")
        val section = config.getSection("messages")!!
        assertEquals("old", plain.serialize(section.getComponent("join")))

        edit(file, "messages:\n  join: \"new\"\n")

        assertEquals("new", plain.serialize(section.getComponent("join")))
        assertEquals("new", config.getString("messages.join"))
    }

    @Test
    fun `unsaved changes are not replaced by an edited file`() {
        val (file, config) = load("msg: \"old\"\nother: 1\n")
        config.set("other", 2)

        edit(file, "msg: \"new\"\nother: 1\n")

        assertEquals("old", plain.serialize(config.getComponent("msg")))
        assertEquals(2, config.getInt("other"))
    }

    @Test
    fun `a broken edit keeps the previous messages`() {
        val (file, config) = load("msg: \"old\"\n")

        edit(file, "msg: [unclosed\n")

        assertEquals("old", plain.serialize(config.getComponent("msg")))
    }
}
