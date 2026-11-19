package de.quadrixyt.qavlen.paper

import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.tree.LiteralCommandNode
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.brigadier.BrigadierPlatform
import de.quadrixyt.qavlen.brigadier.BrigadierTree
import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.CommandArgument
import de.quadrixyt.qavlen.commands.CommandDefinition
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.CommandSource
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList
import com.mojang.brigadier.arguments.ArgumentType as NativeType

/**
 * Registers Qavlen commands as native Brigadier commands through Paper's lifecycle API.
 *
 * Create it in `onEnable` and register commands there. Commands are re-registered whenever Paper rebuilds its
 * command tree, for example on `/minecraft:reload`. Players and worlds use Paper's native argument types; other
 * Qavlen types behave as described in [BrigadierTree].
 *
 * Creating an instance adds `Player` and `World` argument types to the dispatcher's registry, so annotated
 * commands can declare parameters of those types.
 */
public class PaperCommands internal constructor(
    server: Server,
    public val dispatcher: CommandDispatcher,
    public val messages: AdventureMessages,
) {
    private val commands = CopyOnWriteArrayList<CommandDefinition>()
    private val tree = BrigadierTree(dispatcher, Platform())

    @JvmOverloads
    public constructor(
        plugin: Plugin,
        dispatcher: CommandDispatcher,
        messages: AdventureMessages = AdventureMessages(dispatcher.messages.translations),
    ) : this(plugin.server, dispatcher, messages) {
        plugin.lifecycleManager.registerEventHandler(LifecycleEvents.COMMANDS) { event ->
            val registrar = event.registrar()
            commands.forEach { command -> registrar.register(node(command), describe(command), command.aliases) }
        }
    }

    init {
        dispatcher.argumentTypes.register(PlayerArgument(server))
        dispatcher.argumentTypes.register(WorldArgument(server))
    }

    /** Registers [command] with the dispatcher and the server. */
    public fun register(command: CommandDefinition): CommandDefinition = dispatcher.register(command).also(commands::add)

    /** Registers an instance of an `@Command` class. */
    public fun register(annotated: Any): CommandDefinition = dispatcher.register(annotated).also(commands::add)

    /** Wraps a Paper command source for use with the dispatcher. */
    public fun source(stack: CommandSourceStack): PaperCommandSource = PaperCommandSource(stack, messages)

    /** Builds the Brigadier node of [command], for plugins that register nodes themselves. */
    public fun node(command: CommandDefinition): LiteralCommandNode<CommandSourceStack> = tree.build(command)

    private fun describe(command: CommandDefinition): String? = command.description.takeIf(String::isNotEmpty)?.let { description ->
        val translations = dispatcher.messages.translations
        if (translations.contains(translations.defaultLocale, description)) {
            translations.format(translations.defaultLocale, description)
        } else {
            description
        }
    }

    private inner class Platform : BrigadierPlatform<CommandSourceStack> {
        override fun source(source: CommandSourceStack): CommandSource = this@PaperCommands.source(source)

        override fun nativeType(type: ArgumentType<*>): NativeType<*>? = when (type) {
            is PlayerArgument -> ArgumentTypes.player()
            is WorldArgument -> ArgumentTypes.world()
            else -> null
        }

        override fun convert(context: CommandContext<CommandSourceStack>, argument: CommandArgument<*>): Any? {
            if (argument.type !is PlayerArgument) return null
            return context.getArgument(argument.name, PlayerSelectorArgumentResolver::class.java)
                .resolve(context.source).firstOrNull()
                ?: throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to argument.name))
        }
    }
}
