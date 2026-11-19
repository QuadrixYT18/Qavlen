package de.quadrixyt.qavlen.velocity

import com.velocitypowered.api.command.BrigadierCommand
import com.velocitypowered.api.command.CommandSource
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.server.RegisteredServer
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.brigadier.BrigadierPlatform
import de.quadrixyt.qavlen.brigadier.BrigadierTree
import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.CommandDefinition
import de.quadrixyt.qavlen.commands.CommandDescriptor
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.language.Translations
import net.kyori.adventure.text.Component
import java.util.Locale
import de.quadrixyt.qavlen.commands.CommandSource as QavlenSource

/**
 * Registers Qavlen commands with Velocity's command manager as Brigadier commands.
 *
 * Creating an instance adds `Player` and `RegisteredServer` argument types to the dispatcher's registry. Velocity
 * runs commands on its own thread pool, so executors may block briefly but must stay thread-safe.
 */
public class VelocityCommands @JvmOverloads constructor(
    private val server: ProxyServer,
    private val plugin: Any,
    public val dispatcher: CommandDispatcher,
    public val messages: AdventureMessages = AdventureMessages(dispatcher.messages.translations),
) {
    private val tree = BrigadierTree(
        dispatcher,
        object : BrigadierPlatform<CommandSource> {
            override fun source(source: CommandSource): QavlenSource = this@VelocityCommands.source(source)
        },
    )

    init {
        dispatcher.argumentTypes.register(ProxyPlayerArgument(server))
        dispatcher.argumentTypes.register(ServerArgument(server))
    }

    /** Registers [command] with the dispatcher and the proxy. */
    public fun register(command: CommandDefinition): CommandDefinition {
        dispatcher.register(command)
        val meta = server.commandManager.metaBuilder(command.name)
            .aliases(*command.aliases.toTypedArray())
            .plugin(plugin)
            .build()
        server.commandManager.register(meta, BrigadierCommand(tree.build(command)))
        return command
    }

    /** Registers an instance of an `@Command` class. */
    public fun register(annotated: Any): CommandDefinition =
        register(CommandDescriptor.definitionOf(annotated, dispatcher.argumentTypes))

    /** Removes [name] from the proxy and the dispatcher. */
    public fun unregister(name: String) {
        server.commandManager.unregister(name)
        dispatcher.unregister(name)
    }

    public fun source(source: CommandSource): VelocityCommandSource = VelocityCommandSource(source, messages)
}

/** A Velocity command source. Players use their client locale; the console uses the default locale. */
public class VelocityCommandSource(public val source: CommandSource, private val messages: AdventureMessages) : QavlenSource {
    override val name: String get() = (source as? Player)?.username ?: "console"

    override val id: String get() = (source as? Player)?.uniqueId?.toString() ?: "console"

    override val locale: Locale get() = (source as? Player)?.effectiveLocale ?: messages.translations.defaultLocale

    override val handle: Any get() = source

    override fun hasPermission(permission: String): Boolean = source.hasPermission(permission)

    override fun sendMessage(message: String) {
        source.sendMessage(Component.text(message))
    }

    override fun sendTranslated(translations: Translations, key: String, placeholders: Map<String, Any?>) {
        val renderer = if (translations === messages.translations) messages else AdventureMessages(translations)
        source.sendMessage(renderer.render(locale, key, placeholders))
    }
}

/** A player connected to the proxy, by exact name. */
public class ProxyPlayerArgument(private val server: ProxyServer) : ArgumentType<Player> {
    override val valueType: Class<Player> get() = Player::class.java

    override fun parse(input: String, source: QavlenSource): Player = server.getPlayer(input).orElseThrow {
        CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to input))
    }

    override fun suggest(input: String, source: QavlenSource): List<String> = server.allPlayers.map(Player::getUsername)
}

/** A backend server registered with the proxy, by name. */
public class ServerArgument(private val server: ProxyServer) : ArgumentType<RegisteredServer> {
    override val valueType: Class<RegisteredServer> get() = RegisteredServer::class.java

    override fun parse(input: String, source: QavlenSource): RegisteredServer = server.getServer(input).orElseThrow {
        CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to input))
    }

    override fun suggest(input: String, source: QavlenSource): List<String> = server.allServers.map { it.serverInfo.name }
}
