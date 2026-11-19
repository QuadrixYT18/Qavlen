package de.quadrixyt.qavlen.brigadier

import com.mojang.brigadier.Command
import com.mojang.brigadier.arguments.BoolArgumentType
import com.mojang.brigadier.arguments.DoubleArgumentType
import com.mojang.brigadier.arguments.IntegerArgumentType
import com.mojang.brigadier.arguments.LongArgumentType
import com.mojang.brigadier.arguments.StringArgumentType
import com.mojang.brigadier.builder.ArgumentBuilder
import com.mojang.brigadier.builder.LiteralArgumentBuilder
import com.mojang.brigadier.builder.RequiredArgumentBuilder
import com.mojang.brigadier.context.CommandContext
import com.mojang.brigadier.suggestion.SuggestionProvider
import com.mojang.brigadier.tree.LiteralCommandNode
import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.BooleanArgument
import de.quadrixyt.qavlen.commands.CommandArgument
import de.quadrixyt.qavlen.commands.CommandDefinition
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.CommandResult
import de.quadrixyt.qavlen.commands.CommandSource
import de.quadrixyt.qavlen.commands.DoubleArgument
import de.quadrixyt.qavlen.commands.IntegerArgument
import de.quadrixyt.qavlen.commands.LongArgument
import de.quadrixyt.qavlen.commands.StringArgument
import com.mojang.brigadier.arguments.ArgumentType as NativeType

/** Platform hooks for [BrigadierTree]. */
public interface BrigadierPlatform<S> {
    /** Wraps a native command source. */
    public fun source(source: S): CommandSource

    /** A native argument type for platform-specific Qavlen types, or `null` to fall back to the defaults. */
    public fun nativeType(type: ArgumentType<*>): NativeType<*>? = null

    /**
     * Converts a parsed native value of a type returned by [nativeType], for example a player selector into a
     * player. Return `null` when the argument is not platform-specific.
     *
     * @throws CommandException when the value does not resolve
     */
    public fun convert(context: CommandContext<S>, argument: CommandArgument<*>): Any? = null
}

/**
 * Turns [CommandDefinition]s into Brigadier trees for platforms that use Brigadier, such as Paper and Velocity.
 *
 * Integer, long, decimal, boolean and string arguments become native Brigadier types; everything else is sent as a
 * string with Qavlen suggestions and parsed by its [ArgumentType]. Execution goes through
 * [CommandDispatcher.invoke], so permissions, cooldowns and translated errors match all other platforms.
 * Permission checks are also attached as Brigadier requirements, which hides commands from clients that cannot use them.
 * Incomplete input, such as a missing required argument, is answered with the translated usage line.
 */
public class BrigadierTree<S>(private val dispatcher: CommandDispatcher, private val platform: BrigadierPlatform<S>) {
    /** Builds the node for [command] under its own name. Aliases are registered by the platform. */
    public fun build(command: CommandDefinition): LiteralCommandNode<S> = literal(command.name, listOf(command)).build()

    private fun literal(label: String, path: List<CommandDefinition>): LiteralArgumentBuilder<S> {
        val node = path.last()
        val builder = LiteralArgumentBuilder.literal<S>(label).requires { dispatcher.canUse(platform.source(it), path) }
        for (child in node.subcommands) {
            (listOf(child.name) + child.aliases).forEach { builder.then(literal(it, path + child)) }
        }
        if (node.executor != null && node.arguments.firstOrNull()?.optional != false) {
            builder.executes { execute(it, path, 0) }
        } else {
            builder.executes { usage(it, path) }
        }
        if (node.executor != null) arguments(node.arguments, 0, path)?.let(builder::then)
        return builder
    }

    private fun arguments(arguments: List<CommandArgument<*>>, index: Int, path: List<CommandDefinition>): ArgumentBuilder<S, *>? {
        val argument = arguments.getOrNull(index) ?: return null
        val native = nativeType(argument.type)
        val builder = RequiredArgumentBuilder.argument<S, Any>(argument.name, (native ?: fallback(argument.type)).erased())
        if (native == null) builder.suggests(suggestions(argument.type))
        val provided = index + 1
        if (arguments.getOrNull(provided)?.optional != false) {
            builder.executes { execute(it, path, provided) }
        } else {
            builder.executes { usage(it, path) }
        }
        arguments(arguments, provided, path)?.let(builder::then)
        return builder
    }

    /** Answers incomplete input, such as a missing required argument, with the translated usage line. */
    private fun usage(context: CommandContext<S>, path: List<CommandDefinition>): Int {
        dispatcher.messages.send(platform.source(context.source), CommandException.USAGE, mapOf("usage" to dispatcher.usage(path)))
        return 0
    }

    private fun execute(context: CommandContext<S>, path: List<CommandDefinition>, provided: Int): Int {
        val source = platform.source(context.source)
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

    private fun value(context: CommandContext<S>, argument: CommandArgument<*>, source: CommandSource): Any {
        platform.convert(context, argument)?.let { return it }
        val type = argument.type
        return if (nativeType(type) != null) {
            context.getArgument(argument.name, type.valueType)
        } else {
            type.parse(context.getArgument(argument.name, String::class.java), source)
        }
    }

    private fun nativeType(type: ArgumentType<*>): NativeType<*>? = platform.nativeType(type) ?: when (type) {
        is IntegerArgument -> IntegerArgumentType.integer(type.min, type.max)
        is LongArgument -> LongArgumentType.longArg(type.min, type.max)
        is DoubleArgument -> DoubleArgumentType.doubleArg(type.min, type.max)
        is BooleanArgument -> BoolArgumentType.bool()
        is StringArgument -> if (type.greedy) StringArgumentType.greedyString() else StringArgumentType.string()
        else -> null
    }

    private fun fallback(type: ArgumentType<*>): NativeType<*> =
        if (type.greedy) StringArgumentType.greedyString() else StringArgumentType.string()

    private fun suggestions(type: ArgumentType<*>): SuggestionProvider<S> = SuggestionProvider { context, builder ->
        dispatcher.suggestions(type, builder.remaining, platform.source(context.source)).forEach(builder::suggest)
        builder.buildFuture()
    }

    @Suppress("UNCHECKED_CAST")
    private fun NativeType<*>.erased(): NativeType<Any> = this as NativeType<Any>
}
