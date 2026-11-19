package de.quadrixyt.qavlen.velocity

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandMeta
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.ProxyServer
import de.quadrixyt.qavlen.commands.ArgumentTypes
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.argument
import de.quadrixyt.qavlen.commands.command
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals
import com.mojang.brigadier.CommandDispatcher as Brigadier

class VelocityCommandsTest {
    private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> T::class.java.simpleName
                else -> handler(method.name, args ?: emptyArray())
            }
        } as T

    @Test
    fun `registers brigadier commands with the proxy`() {
        val registered = mutableListOf<BrigadierCommand>()
        val metaBuilder = proxy<CommandMeta.Builder> { name, _ -> if (name == "build") proxy<CommandMeta> { _, _ -> null } else null }
        lateinit var builderProxy: CommandMeta.Builder
        builderProxy = proxy { name, _ -> if (name == "build") metaBuilder.build() else builderProxy }
        val commandManager = proxy<com.velocitypowered.api.command.CommandManager> { name, args ->
            when (name) {
                "metaBuilder" -> builderProxy
                "register" -> registered.add(args[1] as BrigadierCommand).let { null }
                else -> null
            }
        }
        val server = proxy<ProxyServer> { name, _ -> if (name == "getCommandManager") commandManager else null }
        val log = mutableListOf<String>()
        val commands = VelocityCommands(server, Any(), CommandDispatcher())
        commands.register(
            command("lobby") { root ->
                root.argument("times", ArgumentTypes.integer(1, 3)).executes { log += "lobby ${it.argument<Int>("times")}" }
            },
        )
        val messages = mutableListOf<String>()
        val console = proxy<CommandSource> { name, args ->
            when (name) {
                "hasPermission" -> true
                "sendMessage" -> messages.add(PlainTextComponentSerializer.plainText().serialize(args[0] as Component)).let { null }
                else -> null
            }
        }

        val brigadier = Brigadier<CommandSource>().apply { root.addChild(registered.single().node) }
        assertEquals(1, brigadier.execute("lobby 2", console))
        assertEquals(listOf("lobby 2"), log)
    }
}
