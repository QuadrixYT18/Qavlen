package de.quadrixyt.qavlen.paper

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.BooleanArgument
import de.quadrixyt.qavlen.commands.CommandArgument
import de.quadrixyt.qavlen.commands.CommandDefinition
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.CommandResult
import de.quadrixyt.qavlen.commands.DoubleArgument
import de.quadrixyt.qavlen.commands.IntegerArgument
import de.quadrixyt.qavlen.commands.LongArgument
import de.quadrixyt.qavlen.commands.StringArgument
import io.papermc.paper.command.brigadier.CommandSourceStack
import io.papermc.paper.command.brigadier.Commands
import io.papermc.paper.command.brigadier.argument.ArgumentTypes
import io.papermc.paper.command.brigadier.argument.resolvers.selector.PlayerSelectorArgumentResolver
import io.papermc.paper.plugin.lifecycle.event.types.LifecycleEvents
import org.bukkit.Server
import org.bukkit.plugin.Plugin
import java.util.concurrent.CopyOnWriteArrayList
import com.mojang.brigadier.arguments.ArgumentType as BrigadierType

/**
 * Registers Qavlen commands as native Brigadier commands through Paper's lifecycle API.
 *
 * Create it in `onEnable` (or a bootstrapper) and register commands before the server finishes starting. Commands
 * are re-registered whenever Paper reloads its command tree. Built-in argument types map to native Brigadier and
 * Paper argument types, so clients get syntax highlighting and vanilla-style validation; other types are sent as
 * strings with suggestions and parsed by Qavlen.
 *
 * Creating an instance adds `Player` and `World` argument types to the dispatcher's registry.
 */
public class PaperCommands internal constructor(
    server: Server,
    public val dispatcher: CommandDispatcher,
    public val messages: AdventureMessages,
) {
    private val commands = CopyOnWriteArrayList<CommandDefinition>()

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

    /** Builds the Brigadier node of [command]. Exposed for plugins that register nodes themselves. */
    public fun node(command: CommandDefinition): LiteralCommandNode<CommandSourceStack> = literal(command.name, listOf(command)).build()

    private fun literal(label: String, path: List<CommandDefinition>): LiteralArgumentBuilder<CommandSourceStack> {
        val node = path.last()
        val builder = Commands.literal(label).requires { stack -> dispatcher.canUse(source(stack), path) }
        for (child in node.subcommands) {
            val childPath = path + child
            (listOf(child.name) + child.aliases).forEach { builder.then(literal(it, childPath)) }
        }
        if (node.executor != null) {
            if (node.arguments.firstOrNull()?.optional != false) builder.executes { execute(it, path, 0) }
            arguments(node.arguments, 0, path)?.let(builder::then)
        }
        return builder
    }

    private fun arguments(
        arguments: List<CommandArgument<*>>,
        index: Int,
        path: List<CommandDefinition>,
    ): ArgumentBuilder<CommandSourceStack, *>? {
        val argument = arguments.getOrNull(index) ?: return null
        val builder = Commands.argument(argument.name, brigadierType(argument.type))
        if (native(argument.type) == null) builder.suggests(suggestions(argument.type))
        val provided = index + 1
        if (arguments.getOrNull(provided)?.optional != false) builder.executes { execute(it, path, provided) }
        arguments(arguments, provided, path)?.let(builder::then)
        return builder
    }

    private fun execute(context: CommandContext<CommandSourceStack>, path: List<CommandDefinition>, provided: Int): Int {
        val source = source(context.source)
        val values = linkedMapOf<String, Any>()
        for (argument in path.last().arguments.take(provided)) {
            values[argument.name] = try {
                value(context, argument, source)
            } catch (exception: CommandException) {
                dispatcher.messages.send(source, exception.key, mapOf("argument" to argument.name) + exception.placeholders)
                return 0
            }
        }
        return if (dispatcher.invoke(source, path, values) == CommandResult.SUCCESS) Command.SINGLE_SUCCESS else 0
    }

    private fun value(context: CommandContext<CommandSourceStack>, argument: CommandArgument<*>, source: PaperCommandSource): Any {
        val type = argument.type
        return when {
            type is PlayerArgument -> context.getArgument(argument.name, PlayerSelectorArgumentResolver::class.java)
                .resolve(context.source).firstOrNull()
                ?: throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to argument.name))
            native(type) != null -> context.getArgument(argument.name, type.valueType)
            else -> type.parse(context.getArgument(argument.name, String::class.java), source)
        }
    }

    private fun brigadierType(type: ArgumentType<*>): BrigadierType<*> = native(type) ?: when {
        type.greedy -> StringArgumentType.greedyString()
        else -> StringArgumentType.string()
    }

    /** Native Brigadier types for Qavlen's built-in argument types, or `null` to parse a string. */
    private fun native(type: ArgumentType<*>): BrigadierType<*>? = when (type) {
        is IntegerArgument -> IntegerArgumentType.integer(type.min, type.max)
        is LongArgument -> LongArgumentType.longArg(type.min, type.max)
        is DoubleArgument -> DoubleArgumentType.doubleArg(type.min, type.max)
        is BooleanArgument -> BoolArgumentType.bool()
        is PlayerArgument -> ArgumentTypes.player()
        is WorldArgument -> ArgumentTypes.world()
        is StringArgument -> if (type.greedy) StringArgumentType.greedyString() else StringArgumentType.string()
        else -> null
    }

    private fun suggestions(type: ArgumentType<*>): SuggestionProvider<CommandSourceStack> = SuggestionProvider { context, builder ->
        val remaining = builder.remaining
        type.suggest(remaining, source(context.source))
            .filter { it.startsWith(remaining, ignoreCase = true) }
            .forEach(builder::suggest)
        builder.buildFuture()
    }

    private fun describe(command: CommandDefinition): String? = command.description.takeIf(String::isNotEmpty)?.let { description ->
        val translations = dispatcher.messages.translations
        if (translations.contains(translations.defaultLocale, description)) {
            translations.format(translations.defaultLocale, description)
        } else {
            description
        }
    }
}
