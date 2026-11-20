package de.quadrixyt.qavlen.config

import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentIteratorType
import net.kyori.adventure.text.TextComponent
import net.kyori.adventure.text.format.NamedTextColor
import net.kyori.adventure.text.format.TextColor
import net.kyori.adventure.text.format.TextDecoration
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.junit.jupiter.api.io.TempDir
import java.io.File
import java.lang.invoke.MethodHandles
import java.lang.invoke.MethodType
import java.net.URLClassLoader
import java.nio.file.Path
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ConfigComponentTest {
    @TempDir
    lateinit var directory: Path

    private val plain = PlainTextComponentSerializer.plainText()

    private val config = YamlConfig.parse(
        """
        prefix: "<gray>[Shop]</gray>"
        welcome: "<prefix> <green>Welcome, {player}!</green> You have {coins:number} coins."
        title: "<gradient:#ff0000:#0000ff>Qavlen</gradient> <bold>{name}</bold>"
        braces: "Use {{player}} literally"
        broken: "Hello {player"
        count: "{n:choice:0#no items|1#one item|1<{n} items}"
        lore:
          - "<gray>Price: {price}"
          - "<prefix> <yellow>Click to buy"
        messages:
          prefix: "<red>[Nested]</red>"
          join: "<prefix> {player} joined"
        """.trimIndent(),
    )

    @Test
    fun `getString keeps returning the raw text`() {
        assertEquals("<gray>[Shop]</gray>", config.getString("prefix"))
        assertEquals("fallback", config.getString("missing", "fallback"))
    }

    @Test
    fun `renders MiniMessage tags gradients and formatting`() {
        val component = config.requireComponent("title", "name" to "Alex")

        val parts = component.iterator(ComponentIteratorType.DEPTH_FIRST).asSequence().toList()
        assertEquals("Qavlen Alex", plain.serialize(component))
        assertEquals(TextColor.color(0xFF0000), parts.filterIsInstance<TextComponent>().first { it.content() == "Q" }.color())
        assertTrue(parts.any { it.hasDecoration(TextDecoration.BOLD) && plain.serialize(it) == "Alex" })
    }

    @Test
    fun `renders the prefix and formatted placeholders`() {
        val component = config.getComponent("welcome", "player" to "Alex", "coins" to 1500)

        assertEquals("[Shop] Welcome, Alex! You have 1,500 coins.", plain.serialize(component!!))
    }

    @Test
    fun `placeholder values cannot inject tags`() {
        val component = config.requireComponent("welcome", mapOf("player" to "<red><click:run_command:/op me>x</red>", "coins" to 0))

        assertEquals("[Shop] Welcome, <red><click:run_command:/op me>x</red>! You have 0 coins.", plain.serialize(component))
        assertTrue(component.iterator(ComponentIteratorType.DEPTH_FIRST).asSequence().none { it.clickEvent() != null })
    }

    @Test
    fun `placeholder values cannot close the generated tags`() {
        val component = config.requireComponent("title", "name" to "</qavlen_arg_0><click:open_url:'https://x'>y")

        assertEquals("Qavlen </qavlen_arg_0><click:open_url:'https://x'>y", plain.serialize(component))
    }

    @Test
    fun `component values keep their style`() {
        val component = config.requireComponent("title", "name" to Component.text("Diamond", NamedTextColor.AQUA))

        assertEquals("Qavlen Diamond", plain.serialize(component))
    }

    @Test
    fun `missing values and placeholders behave predictably`() {
        assertNull(config.getComponent("missing"))
        val failure = assertFailsWith<ConfigException> { config.requireComponent("missing") }
        assertEquals("Missing message 'missing'", failure.message)
        assertEquals("[Shop] Welcome, {player}! You have {coins} coins.", plain.serialize(config.requireComponent("welcome")))
        assertEquals("Use {player} literally", plain.serialize(config.requireComponent("braces", "player" to "Alex")))
        assertEquals("3 items", plain.serialize(config.requireComponent("count", "n" to 3)))
    }

    @Test
    fun `malformed messages and non-text values are reported with their path`() {
        val malformed = assertFailsWith<ConfigException> { config.getComponent("broken") }
        assertTrue(malformed.message!!.startsWith("Invalid message at 'broken'"), malformed.message)

        val section = assertFailsWith<ConfigValueException> { config.getComponent("messages") }
        assertEquals("messages", section.valuePath)
    }

    @Test
    fun `sections resolve messages and the prefix relative to themselves`() {
        val messages = config.getSection("messages")!!

        assertEquals("[Nested] Alex joined", plain.serialize(messages.requireComponent("join", "player" to "Alex")))
        val failure = assertFailsWith<ConfigException> { messages.requireComponent("leave") }
        assertEquals("Missing message 'messages.leave'", failure.message)
    }

    @Test
    fun `prefix without a configured value renders empty`() {
        val config = YamlConfig.parse("join: \"<prefix>{player} joined\"")

        assertEquals("Alex joined", plain.serialize(config.requireComponent("join", "player" to "Alex")))
    }

    @Test
    fun `lists and multi-line texts render one component per line`() {
        val lore = config.getComponentList("lore", "price" to 1200)

        assertEquals(listOf("Price: 1,200", "[Shop] Click to buy"), lore.map(plain::serialize))
        assertEquals(listOf("a", "b"), YamlConfig.parse("text: \"a\\nb\"").getComponentList("text").map(plain::serialize))
        assertEquals(emptyList(), config.getComponentList("missing"))
    }

    @Test
    fun `JSON files and loaded files support components`() {
        val json = JsonConfig.parse("""{"prefix": "<gold>!</gold>", "hello": "<prefix> Hi {name}"}""")
        assertEquals("! Hi <b>", plain.serialize(json.requireComponent("hello", "name" to "<b>")))

        val path = directory.resolve("messages.yml")
        path.writeText("motd: \"<rainbow>Welcome</rainbow>\"\n")
        assertEquals("Welcome", plain.serialize(YamlConfig.load(path).requireComponent("motd")))
    }

    @Test
    fun `configurations work without Adventure on the classpath`() {
        // Adventure is compileOnly; plain configuration access must never need it.
        val classpath = System.getProperty("java.class.path").split(File.pathSeparator)
            .filterNot { "adventure" in it || "examination" in it || "option" in it.substringAfterLast(File.separator) }
            .map { File(it).toURI().toURL() }
        URLClassLoader(classpath.toTypedArray(), ClassLoader.getPlatformClassLoader()).use { loader ->
            assertFailsWith<ClassNotFoundException> { loader.loadClass("net.kyori.adventure.text.Component") }
            // Method handles link single methods like compiled calls; reflection would resolve every signature.
            val lookup = MethodHandles.publicLookup()
            val yaml = loader.loadClass("de.quadrixyt.qavlen.config.YamlConfig")
            val section = loader.loadClass("de.quadrixyt.qavlen.config.ConfigSection")
            val loaded = lookup.findStatic(yaml, "parse", MethodType.methodType(yaml, String::class.java)).invoke("message: \"<red>Hi\"")
            val getString = lookup.findVirtual(section, "getString", MethodType.methodType(String::class.java, String::class.java))

            assertEquals("<red>Hi", getString.invoke(loaded, "message"))
        }
    }
}
