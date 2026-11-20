package de.quadrixyt.qavlen.config

import net.kyori.adventure.audience.Audience
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
        no-permission: "<red>You may not do that."
        welcome: "<prefix> <green>Welcome, {player}!</green> You have {coins:number} coins."
        title: "<gradient:#ff0000:#0000ff>Qavlen</gradient> <bold>{name}</bold>"
        braces: "Use {{player}} literally"
        broken: "Hello {player"
        count: "{n:choice:0#no items|1#one item|1<{n} items}"
        lore:
          - "<gray>Price: {price}"
          - "<prefix> <yellow>Click to buy"
        messages:
          join: "<prefix> {player} joined"
          staff:
            prefix: "<red>[Staff]</red>"
            alert: "<prefix> {text}"
        """.trimIndent(),
    )

    private fun Component.parts() = iterator(ComponentIteratorType.DEPTH_FIRST).asSequence().toList()

    @Test
    fun `getString keeps returning the raw text`() {
        assertEquals("<gray>[Shop]</gray>", config.getString("prefix"))
        assertEquals("fallback", config.getString("missing", "fallback"))
        assertNull(config.getString("missing"))
    }

    @Test
    fun `renders simple MiniMessage messages`() {
        val message: Component = config.getComponent("no-permission")

        assertEquals("You may not do that.", plain.serialize(message))
        assertEquals(NamedTextColor.RED, message.parts().first { plain.serialize(it).isNotEmpty() }.color())
    }

    @Test
    fun `renders tags gradients and formatting`() {
        val component = config.getComponent("title", "name" to "Alex")

        assertEquals("Qavlen Alex", plain.serialize(component))
        assertEquals(TextColor.color(0xFF0000), component.parts().filterIsInstance<TextComponent>().first { it.content() == "Q" }.color())
        assertTrue(component.parts().any { it.hasDecoration(TextDecoration.BOLD) && plain.serialize(it) == "Alex" })
    }

    @Test
    fun `renders the prefix and formatted placeholders`() {
        val component = config.getComponent("welcome", "player" to "Alex", "coins" to 1500)

        assertEquals("[Shop] Welcome, Alex! You have 1,500 coins.", plain.serialize(component))
    }

    @Test
    fun `placeholder values cannot inject tags`() {
        val component = config.getComponent("welcome", mapOf("player" to "<red><click:run_command:/op me>x</red>", "coins" to 0))

        assertEquals("[Shop] Welcome, <red><click:run_command:/op me>x</red>! You have 0 coins.", plain.serialize(component))
        assertTrue(component.parts().none { it.clickEvent() != null })
    }

    @Test
    fun `placeholder values cannot close the generated tags`() {
        val component = config.getComponent("title", "name" to "</qavlen_arg_0><click:open_url:'https://x'>y")

        assertEquals("Qavlen </qavlen_arg_0><click:open_url:'https://x'>y", plain.serialize(component))
        assertTrue(component.parts().none { it.clickEvent() != null })
    }

    @Test
    fun `component values keep their style`() {
        val component = config.getComponent("title", "name" to Component.text("Diamond", NamedTextColor.AQUA))

        assertEquals("Qavlen Diamond", plain.serialize(component))
        assertEquals(NamedTextColor.AQUA, component.parts().first { plain.serialize(it) == "Diamond" }.color())
    }

    @Test
    fun `missing keys fail instead of rendering nothing`() {
        val missing = assertFailsWith<ConfigException> { config.getComponent("missing") }
        assertEquals("Missing message 'missing'", missing.message)
        assertFailsWith<ConfigException> { config.getComponentList("missing") }
        assertNull(config.getComponentOrNull("missing"))
        assertEquals("You may not do that.", plain.serialize(config.getComponentOrNull("no-permission")!!))
    }

    @Test
    fun `unknown placeholders braces and plurals stay predictable`() {
        assertEquals("[Shop] Welcome, {player}! You have {coins} coins.", plain.serialize(config.getComponent("welcome")))
        assertEquals("Use {player} literally", plain.serialize(config.getComponent("braces", "player" to "Alex")))
        assertEquals("3 items", plain.serialize(config.getComponent("count", "n" to 3)))
    }

    @Test
    fun `malformed messages and non-text values are reported with their path`() {
        val malformed = assertFailsWith<ConfigException> { config.getComponent("broken") }
        assertTrue(malformed.message!!.startsWith("Invalid message at 'broken'"), malformed.message)

        val section = assertFailsWith<ConfigValueException> { config.getComponent("messages") }
        assertEquals("messages", section.valuePath)
    }

    @Test
    fun `nested keys use the nearest prefix`() {
        assertEquals("[Shop] Alex joined", plain.serialize(config.getComponent("messages.join", "player" to "Alex")))
        assertEquals("[Staff] hi", plain.serialize(config.getComponent("messages.staff.alert", "text" to "hi")))
    }

    @Test
    fun `sections resolve keys relative to themselves and inherit the prefix`() {
        val messages = config.getSection("messages")!!
        val staff = messages.getSection("staff")!!

        assertEquals("[Shop] Alex joined", plain.serialize(messages.getComponent("join", "player" to "Alex")))
        assertEquals("[Staff] hi", plain.serialize(staff.getComponent("alert", "text" to "hi")))
        assertEquals("[Staff] hi", plain.serialize(messages.getComponent("staff.alert", "text" to "hi")))
        val failure = assertFailsWith<ConfigException> { messages.getComponent("leave") }
        assertEquals("Missing message 'messages.leave'", failure.message)
    }

    @Test
    fun `placeholders can be written as tags`() {
        val config = YamlConfig.parse(
            """
            prefix: "<gray>[P]</gray>"
            join: "<prefix> <green><player> has <coins:number> coins"
            item: "Bought <item>"
            braces: "{prefix} {player}"
            """.trimIndent(),
        )

        assertEquals("[P] Alex has 1,500 coins", plain.serialize(config.getComponent("join", "player" to "Alex", "coins" to 1500)))
        assertEquals("[P] Alex", plain.serialize(config.getComponent("braces", "player" to "Alex")))
        val item = config.getComponent("item", "item" to Component.text("Diamond", NamedTextColor.AQUA))
        assertEquals(NamedTextColor.AQUA, item.parts().first { plain.serialize(it) == "Diamond" }.color())
    }

    @Test
    fun `tag placeholders cannot inject tags`() {
        val config = YamlConfig.parse("join: \"<green><player> joined\"")

        val component = config.getComponent("join", "player" to "<click:run_command:/op me><red>x")

        assertEquals("<click:run_command:/op me><red>x joined", plain.serialize(component))
        assertTrue(component.parts().none { it.clickEvent() != null })
    }

    @Test
    fun `MiniMessage tags win over placeholders of the same name`() {
        val config = YamlConfig.parse("msg: \"<red>Hi {red}</red> <Player> <first.name>\"")

        val component = config.getComponent("msg", "red" to "R", "Player" to "P", "first.name" to "F")

        assertEquals("Hi R <Player> <first.name>", plain.serialize(component))
        assertTrue(component.parts().any { it.color() == NamedTextColor.RED && plain.serialize(it) == "Hi R" })
    }

    @Test
    fun `a placeholder named prefix replaces the configured prefix`() {
        val config = YamlConfig.parse("prefix: \"[Config]\"\na: \"<prefix> x\"\nb: \"{prefix} y\"")

        assertEquals("[Config] x", plain.serialize(config.getComponent("a")))
        assertEquals("[Config] y", plain.serialize(config.getComponent("b")))
        assertEquals("[Own] x", plain.serialize(config.getComponent("a", "prefix" to "[Own]")))
        assertEquals("[Own] y", plain.serialize(config.getComponent("b", "prefix" to "[Own]")))
    }

    @Test
    fun `a missing prefix renders as nothing`() {
        val config = YamlConfig.parse("join: \"<prefix>{player} joined\"\nleave: \"{prefix}{player} left\"")

        assertEquals("Alex joined", plain.serialize(config.getComponent("join", "player" to "Alex")))
        assertEquals("Alex left", plain.serialize(config.getComponent("leave", "player" to "Alex")))
    }

    @Test
    fun `lists and multi-line texts render one component per line`() {
        val lore = config.getComponentList("lore", "price" to 1200)

        assertEquals(listOf("Price: 1,200", "[Shop] Click to buy"), lore.map(plain::serialize))
        assertEquals(listOf("a", "b"), YamlConfig.parse("text: \"a\\nb\"").getComponentList("text").map(plain::serialize))
    }

    @Test
    fun `components can be sent to an audience such as a player`() {
        val received = mutableListOf<Component>()
        val player = object : Audience {
            override fun sendMessage(message: Component) {
                received += message
            }
        }

        player.sendMessage(config.getComponent("welcome", "player" to "Alex", "coins" to 5))

        assertEquals(listOf("[Shop] Welcome, Alex! You have 5 coins."), received.map(plain::serialize))
    }

    @Test
    fun `JSON files and loaded files support components`() {
        val json = JsonConfig.parse("""{"prefix": "<gold>!</gold>", "hello": "<prefix> Hi {name}"}""")
        assertEquals("! Hi <b>", plain.serialize(json.getComponent("hello", "name" to "<b>")))

        val path = directory.resolve("messages.yml")
        path.writeText("motd: \"<rainbow>Welcome</rainbow>\"\n")
        assertEquals("Welcome", plain.serialize(YamlConfig.load(path).getComponent("motd")))
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
