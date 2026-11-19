package de.quadrixyt.qavlen.paper

import com.mojang.brigadier.exceptions.CommandSyntaxException
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.ArgumentTypes
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.argument
import de.quadrixyt.qavlen.commands.argumentOrNull
import de.quadrixyt.qavlen.commands.command
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import org.bukkit.Server
import org.bukkit.command.CommandSender
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import com.mojang.brigadier.CommandDispatcher as Brigadier

class PaperCommandsTest {
    enum class Mode { FAST, SAFE }

    private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> System.identityHashCode(this)
                "equals" -> false
                "toString" -> T::class.java.simpleName
                else -> handler(method.name, args ?: emptyArray())
            }
        } as T

    private val received = mutableListOf<String>()
    private val log = mutableListOf<String>()

    private fun source(permissions: Set<String>): CommandSourceStack {
        val sender = proxy<CommandSender> { name, args ->
            when (name) {
                "getName" -> "console"
                "hasPermission" -> args[0] in permissions
                "sendMessage" -> received += PlainTextComponentSerializer.plainText().serialize(args[0] as Component)
                else -> null
            }
        }
        return proxy { name, _ -> if (name == "getSender") sender else null }
    }

    private fun paperCommands(): PaperCommands {
        val dispatcher = CommandDispatcher()
        return PaperCommands(proxy<Server> { _, _ -> null }, dispatcher, AdventureMessages(dispatcher.messages.translations))
    }

    @Test
    fun `builds a native tree that runs through the dispatcher`() {
        val commands = paperCommands()
        val definition = commands.register(
            command("coins") { root ->
                root.aliases("money")
                root.subcommand("give") { give ->
                    give.permission("coins.admin")
                        .argument("amount", ArgumentTypes.integer(1, 100))
                        .optionalArgument("mode", ArgumentTypes.enumeration(Mode::class.java))
                        .executes { log += "${it.argument<Int>("amount")}:${it.argumentOrNull<Mode>("mode")}" }
                }
            },
        )
        val brigadier = Brigadier<CommandSourceStack>().apply { root.addChild(commands.node(definition)) }
        val admin = source(setOf("coins.admin"))

        assertEquals(1, brigadier.execute("coins give 5", admin))
        assertEquals(1, brigadier.execute("coins give 7 safe", admin))
        assertEquals(0, brigadier.execute("coins give 7 slow", admin))
        assertEquals(0, brigadier.execute("coins give", admin))
        assertEquals(0, brigadier.execute("coins", admin))
        assertFailsWith<CommandSyntaxException> { brigadier.execute("coins give 500", admin) }
        assertFailsWith<CommandSyntaxException> { brigadier.execute("coins give 5", source(emptySet())) }

        assertEquals(listOf("5:null", "7:SAFE"), log)
        assertEquals(
            listOf("'slow' is not valid. Choose one of: fast, safe.", "Usage: /coins give <amount> [mode]", "Usage: /coins <give>"),
            received,
        )
        val suggestions = brigadier.getCompletionSuggestions(brigadier.parse("coins give 5 s", admin)).get()
        assertEquals(listOf("safe"), suggestions.list.map { it.text })
    }
}
