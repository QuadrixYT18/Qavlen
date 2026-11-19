package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.language.Translations
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class CommandFeaturesTest {
    private val translations = Translations.builder()
        .messages(Locale.ENGLISH, mapOf("shop.buy" to "Buy an item", "shop.closed" to "The shop is closed."))
        .messages(Locale.GERMAN, mapOf("shop.buy" to "Einen Gegenstand kaufen"))
        .build()

    private var open = false
    private val log = mutableListOf<String>()

    private fun shop() = command("shop") { root ->
        root.condition { if (!open) throw CommandException("shop.closed") }
        root.subcommand("buy") {
            it.description("shop.buy").argument("item", ArgumentTypes.word(3, 8)).executes { context -> log += context.argument<String>("item") }
        }
        root.subcommand("admin") { it.permission("shop.admin").description("Manage the shop").executes { } }
        root.subcommand("help") { it.executes(CommandContext::sendHelp) }
    }

    @Test
    fun `conditions apply to subcommands`() {
        val dispatcher = CommandDispatcher(translations).apply { register(shop()) }
        val source = TestSource()

        assertEquals(CommandResult.REJECTED, dispatcher.execute(source, "shop buy apple"))
        open = true
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(source, "shop buy apple"))

        assertEquals(listOf("The shop is closed."), source.messages)
        assertEquals(listOf("apple"), log)
    }

    @Test
    fun `string arguments enforce their length`() {
        open = true
        val dispatcher = CommandDispatcher(translations).apply { register(shop()) }
        val source = TestSource()

        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(source, "shop buy ab"))
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(source, "shop buy watermelon"))

        assertEquals(
            listOf("'ab' must be 3 to 8 characters long.", "'watermelon' must be 3 to 8 characters long."),
            source.messages,
        )
    }

    @Test
    fun `help lists usable commands with translated descriptions`() {
        open = true
        val dispatcher = CommandDispatcher(translations).apply { register(shop()) }
        val german = TestSource(Locale.GERMAN)
        val admin = TestSource(permissions = setOf("shop.admin"))

        assertEquals(
            listOf(CommandHelp("/shop buy <item>", "Einen Gegenstand kaufen"), CommandHelp("/shop help", "")),
            dispatcher.help(german),
        )
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(admin, "shop help"))
        assertEquals(
            listOf("Commands:", "/shop buy <item> - Buy an item", "/shop admin - Manage the shop", "/shop help"),
            admin.messages,
        )
    }

    @Test
    fun `a failing error handler does not hide the failure message`() {
        val dispatcher = CommandDispatcher().apply {
            register(command("boom") { it.executes { error("boom") } })
            errorHandler = CommandErrorHandler { _, _, _ -> error("handler broken") }
        }
        val source = TestSource()

        assertEquals(CommandResult.ERROR, dispatcher.execute(source, "boom"))
        assertTrue(source.messages.single().startsWith("An internal error occurred"))
    }
}
