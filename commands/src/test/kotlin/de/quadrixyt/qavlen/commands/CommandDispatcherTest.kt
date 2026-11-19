package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.language.Translations
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class TestSource(
    override val locale: Locale = Locale.ENGLISH,
    private val permissions: Set<String> = emptySet(),
    override val name: String = "tester",
) : CommandSource {
    val messages = mutableListOf<String>()
    override val id: String get() = name
    override val handle: Any get() = this

    override fun hasPermission(permission: String): Boolean = permission in permissions

    override fun sendMessage(message: String) {
        messages += message
    }
}

enum class Mode { FAST, SAFE }

class CommandDispatcherTest {
    private val log = mutableListOf<String>()

    private fun coins() = command("coins") { root ->
        root.aliases("money")
        root.subcommand("give") { give ->
            give.permission("coins.admin")
                .argument("target", ArgumentTypes.word())
                .argument("amount", ArgumentTypes.integer(1, 1000))
                .optionalArgument("reason", ArgumentTypes.greedyString())
                .executes { context ->
                    log +=
                        "${context.argument<String>(
                            "target",
                        )}:${context.argument<Int>("amount")}:${context.argumentOrNull<String>("reason")}"
                }
        }
        root.subcommand("mode") { mode ->
            mode.argument("mode", ArgumentTypes.enumeration(Mode::class.java)).executes { log += it.argument<Mode>("mode").name }
        }
        root.subcommand("fail") { it.executes { throw IllegalStateException("boom") } }
        root.subcommand("reject") { it.executes { throw CommandException("custom.reason") } }
    }

    @Test
    fun `executes subcommands with typed arguments`() {
        val dispatcher = CommandDispatcher().apply { register(coins()) }
        val admin = TestSource(permissions = setOf("coins.admin"))

        assertEquals(CommandResult.SUCCESS, dispatcher.execute(admin, "/coins give Alex 5"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(admin, "money give Alex 7 for \"quoted\" help"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(admin, "coins mode safe"))

        assertEquals(listOf("Alex:5:null", "Alex:7:for \"quoted\" help", "SAFE"), log)
    }

    @Test
    fun `reports errors with bundled translations`() {
        val dispatcher = CommandDispatcher().apply { register(coins()) }
        val player = TestSource(Locale.GERMAN)
        val admin = TestSource(permissions = setOf("coins.admin"))

        assertEquals(CommandResult.NO_PERMISSION, dispatcher.execute(player, "coins give Alex 5"))
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(admin, "coins give Alex 0"))
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(admin, "coins give Alex lots"))
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(admin, "coins give Alex"))
        assertEquals(CommandResult.INVALID_INPUT, dispatcher.execute(admin, "coins mode slow"))
        assertEquals(CommandResult.UNKNOWN_COMMAND, dispatcher.execute(admin, "bank"))

        assertEquals(listOf("Du hast keine Berechtigung für diesen Befehl."), player.messages)
        assertEquals(
            listOf(
                "The number must be between 1 and 1,000.",
                "'lots' is not a valid number.",
                "Usage: /coins give <target> <amount> [reason]",
                "'slow' is not valid. Choose one of: fast, safe.",
                "Unknown command. Usage: coins",
            ),
            admin.messages,
        )
    }

    @Test
    fun `user translations override bundled messages`() {
        val translations = Translations.builder()
            .messages(Locale.ENGLISH, mapOf("command.no-permission" to "Nope."), namespace = "qavlen")
            .messages(Locale.ENGLISH, mapOf("custom.reason" to "Rejected by rule"))
            .build()
        val dispatcher = CommandDispatcher(translations).apply { register(coins()) }
        val source = TestSource()

        dispatcher.execute(source, "coins give a 1")
        dispatcher.execute(source, "coins reject")

        assertEquals(listOf("Nope.", "Rejected by rule"), source.messages)
    }

    @Test
    fun `unexpected exceptions go to the error handler`() {
        val errors = mutableListOf<Throwable>()
        val dispatcher = CommandDispatcher().apply {
            register(coins())
            errorHandler = CommandErrorHandler { _, _, error -> errors += error }
        }
        val source = TestSource()

        assertEquals(CommandResult.ERROR, dispatcher.execute(source, "coins fail"))
        assertEquals("boom", errors.single().message)
        assertEquals(listOf("An internal error occurred while executing this command."), source.messages)
    }

    @Test
    fun `cooldowns apply per source and can be bypassed`() {
        var now = Instant.parse("2025-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val dispatcher = CommandDispatcher(clock = clock).apply {
            register(command("daily") { it.cooldown(Duration.ofMinutes(90)).executes { log += "claimed" } })
            cooldownBypassPermission = "bypass"
        }
        val alex = TestSource(name = "alex")

        assertEquals(CommandResult.SUCCESS, dispatcher.execute(alex, "daily"))
        assertEquals(CommandResult.COOLDOWN, dispatcher.execute(alex, "daily"))
        assertEquals("Please wait 1h30m before using this command again.", alex.messages.single())
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(TestSource(name = "sam"), "daily"))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(TestSource(name = "alex", permissions = setOf("bypass")), "daily"))
        now = now.plus(Duration.ofMinutes(91))
        assertEquals(CommandResult.SUCCESS, dispatcher.execute(alex, "daily"))
    }

    @Test
    fun `suggests visible commands, subcommands and argument values`() {
        val dispatcher = CommandDispatcher().apply { register(coins()) }
        val player = TestSource()
        val admin = TestSource(permissions = setOf("coins.admin"))

        assertEquals(listOf("coins"), dispatcher.suggest(player, "co"))
        assertEquals(listOf("mode", "fail", "reject"), dispatcher.suggest(player, "coins "))
        assertEquals(listOf("give"), dispatcher.suggest(admin, "coins g"))
        assertEquals(listOf("fast", "safe"), dispatcher.suggest(player, "coins mode "))
        assertEquals(listOf("safe"), dispatcher.suggest(player, "coins mode s"))
    }

    @Test
    fun `sender conversion rejects other sources`() {
        val dispatcher = CommandDispatcher().apply {
            register(command("whoami") { it.executes { context -> context.sender<String>() } })
        }
        val source = TestSource()

        assertEquals(CommandResult.REJECTED, dispatcher.execute(source, "whoami"))
        assertEquals("This command can only be used by a string.", source.messages.single())
    }

    @Test
    fun `definitions reject invalid structures`() {
        assertFailsWith<IllegalArgumentException> { CommandDefinition.builder("Bad Name") }
        assertFailsWith<IllegalArgumentException> {
            command("x") { it.argument("a", ArgumentTypes.word()).subcommand("y") { y -> y.executes {} } }
        }
        assertFailsWith<IllegalArgumentException> {
            command("x") { it.optionalArgument("a", ArgumentTypes.word()).argument("b", ArgumentTypes.word()).executes {} }
        }
        assertFailsWith<IllegalArgumentException> {
            command("x") { it.argument("a", ArgumentTypes.greedyString()).argument("b", ArgumentTypes.word()).executes {} }
        }
        val dispatcher = CommandDispatcher().apply { register(coins()) }
        assertFailsWith<IllegalArgumentException> { dispatcher.register(command("money") { it.executes {} }) }
        assertTrue(dispatcher.unregister("coins"))
        dispatcher.register(command("money") { it.executes {} })
    }

    @Test
    fun `registry resolves built-in types and enums`() {
        val registry = ArgumentTypeRegistry()

        assertEquals(Int::class.javaObjectType, registry.resolve(Int::class.javaPrimitiveType!!).valueType)
        assertEquals(Mode.FAST, registry.resolve(Mode::class.java).parse("FAST", TestSource()))
        assertFailsWith<IllegalArgumentException> { registry.resolve(Thread::class.java) }
    }

    @Test
    fun `concurrent executions cannot bypass a cooldown and failures release it`() {
        val running = java.util.concurrent.CountDownLatch(1)
        val finish = java.util.concurrent.CountDownLatch(1)
        var fail = true
        val dispatcher = CommandDispatcher().apply {
            register(
                command("vote") {
                    it.cooldown(Duration.ofMinutes(5)).executes {
                        if (fail) throw CommandException("custom.reason")
                        running.countDown()
                        finish.await()
                    }
                },
            )
        }
        val source = TestSource(name = "alex")

        assertEquals(CommandResult.REJECTED, dispatcher.execute(source, "vote"))
        fail = false
        val first = java.util.concurrent.CompletableFuture.supplyAsync { dispatcher.execute(source, "vote") }
        running.await()
        assertEquals(CommandResult.COOLDOWN, dispatcher.execute(source, "vote"))
        finish.countDown()

        assertEquals(CommandResult.SUCCESS, first.join())
        assertEquals(CommandResult.COOLDOWN, dispatcher.execute(source, "vote"))
        assertTrue(source.messages.contains("Please wait 5m before using this command again."), source.messages.toString())
    }

    @Test
    fun `cooldowns can be started atomically and expire`() {
        var now = Instant.parse("2025-01-01T00:00:00Z")
        val clock = object : Clock() {
            override fun getZone() = ZoneOffset.UTC
            override fun withZone(zone: java.time.ZoneId) = this
            override fun instant() = now
        }
        val cooldowns = Cooldowns(clock)

        assertTrue(cooldowns.tryStart("a", Duration.ofSeconds(10)))
        assertEquals(false, cooldowns.tryStart("a", Duration.ofSeconds(10)))
        now = now.plusSeconds(11)
        assertTrue(cooldowns.tryStart("a", Duration.ofSeconds(10)))
    }
}
