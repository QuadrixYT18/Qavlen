package de.quadrixyt.qavlen.commands

import java.time.Duration
import java.util.Locale
import java.util.function.Consumer

/** Runs a command after its arguments were parsed and its permission and cooldown were checked. */
public fun interface CommandExecutor {
    public fun execute(context: CommandContext)
}

/**
 * A typed command argument.
 *
 * @property optional optional arguments must follow all required ones
 */
public class CommandArgument<T : Any>(
    public val name: String,
    public val type: ArgumentType<T>,
    public val optional: Boolean,
    public val description: String,
) {
    init {
        require(NAME.matches(name)) { "Invalid argument name '$name'" }
    }

    override fun toString(): String = if (optional) "[$name]" else "<$name>"

    internal companion object {
        val NAME = Regex("[a-z0-9_-]{1,32}")
    }
}

/**
 * An immutable command tree node. A node either has [subcommands] or [arguments], never both, which keeps the
 * structure valid for Brigadier as well as Discord slash commands.
 *
 * [description] may be a translation key; adapters look it up and fall back to the literal text.
 */
public class CommandDefinition private constructor(builder: Builder) {
    public val name: String = builder.name
    public val aliases: List<String> = builder.aliases.toList()
    public val description: String = builder.description
    public val permission: String? = builder.permission
    public val cooldown: Duration? = builder.cooldown
    public val arguments: List<CommandArgument<*>> = builder.arguments.toList()
    public val subcommands: List<CommandDefinition> = builder.subcommands.toList()
    public val executor: CommandExecutor? = builder.executor

    init {
        require(subcommands.isEmpty() || arguments.isEmpty()) {
            "Command '$name' cannot declare both subcommands and arguments"
        }
        require(executor != null || subcommands.isNotEmpty()) { "Command '$name' needs an executor or subcommands" }
        val labels = subcommands.flatMap { listOf(it.name) + it.aliases }
        labels.groupingBy { it }.eachCount().filterValues { it > 1 }.keys.firstOrNull()?.let {
            throw IllegalArgumentException("Command '$name' declares '$it' more than once")
        }
        val argumentNames = arguments.map { it.name }
        require(argumentNames.toSet().size == argumentNames.size) { "Command '$name' has duplicate argument names" }
        val firstOptional = arguments.indexOfFirst { it.optional }
        require(firstOptional < 0 || arguments.drop(firstOptional).all { it.optional }) {
            "Required arguments of '$name' must come before optional ones"
        }
        require(arguments.dropLast(1).none { it.type.greedy }) { "Only the last argument of '$name' may be greedy" }
    }

    /** `true` when [label] is the name or an alias of this command. */
    public fun matches(label: String): Boolean = label.lowercase(Locale.ROOT).let { it == name || it in aliases }

    public fun subcommand(label: String): CommandDefinition? = subcommands.firstOrNull { it.matches(label) }

    /** Usage line such as `give <target> <amount> [reason]` relative to this node. */
    public fun usage(): String = buildList {
        add(name)
        if (subcommands.isNotEmpty()) add(subcommands.joinToString("|", "<", ">") { it.name })
        arguments.mapTo(this) { it.toString() }
    }.joinToString(" ")

    override fun toString(): String = "CommandDefinition(${usage()})"

    public class Builder internal constructor(internal val name: String) {
        internal val aliases = linkedSetOf<String>()
        internal var description = ""
        internal var permission: String? = null
        internal var cooldown: Duration? = null
        internal val arguments = mutableListOf<CommandArgument<*>>()
        internal val subcommands = mutableListOf<CommandDefinition>()
        internal var executor: CommandExecutor? = null

        init {
            require(LABEL.matches(name)) { "Invalid command name '$name': use 1-32 lowercase letters, digits, '-' or '_'" }
        }

        public fun aliases(vararg aliases: String): Builder = apply {
            aliases.forEach { require(LABEL.matches(it)) { "Invalid alias '$it'" } }
            this.aliases += aliases
        }

        public fun description(description: String): Builder = apply { this.description = description }

        public fun permission(permission: String?): Builder = apply { this.permission = permission?.takeIf(String::isNotBlank) }

        /** Minimum time between two executions per [CommandSource.id]. */
        public fun cooldown(cooldown: Duration?): Builder = apply {
            require(cooldown == null || !cooldown.isNegative) { "Cooldown must not be negative" }
            this.cooldown = cooldown?.takeUnless(Duration::isZero)
        }

        @JvmOverloads
        public fun <T : Any> argument(name: String, type: ArgumentType<T>, description: String = ""): Builder =
            apply { arguments += CommandArgument(name, type, false, description) }

        @JvmOverloads
        public fun <T : Any> optionalArgument(name: String, type: ArgumentType<T>, description: String = ""): Builder =
            apply { arguments += CommandArgument(name, type, true, description) }

        public fun subcommand(subcommand: CommandDefinition): Builder = apply { subcommands += subcommand }

        public fun subcommand(name: String, configure: Consumer<Builder>): Builder =
            subcommand(Builder(name).also(configure::accept).build())

        public fun executes(executor: CommandExecutor): Builder = apply { this.executor = executor }

        public fun build(): CommandDefinition = CommandDefinition(this)
    }

    public companion object {
        internal val LABEL = Regex("[a-z0-9_-]{1,32}")

        @JvmStatic
        public fun builder(name: String): Builder = Builder(name)
    }
}

/** Kotlin entry point: `command("coins") { it.subcommand("give") { ... } }`. */
public fun command(name: String, configure: Consumer<CommandDefinition.Builder>): CommandDefinition =
    CommandDefinition.builder(name).also(configure::accept).build()
