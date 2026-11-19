package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.core.Durations
import de.quadrixyt.qavlen.language.Translations
import java.time.Clock
import java.time.Duration
import java.util.Locale
import java.util.concurrent.ConcurrentHashMap

/** Outcome of [CommandDispatcher.execute] and [CommandDispatcher.invoke]. */
public enum class CommandResult {
    SUCCESS,
    UNKNOWN_COMMAND,
    NO_PERMISSION,
    INVALID_INPUT,
    COOLDOWN,

    /** The executor threw [CommandException]. */
    REJECTED,

    /** The executor threw an unexpected exception, which was passed to the error handler. */
    ERROR,
}

/** Receives unexpected exceptions thrown by executors. */
public fun interface CommandErrorHandler {
    public fun handle(source: CommandSource, command: List<CommandDefinition>, error: Throwable)
}

/**
 * Holds registered commands and runs them.
 *
 * [execute] and [suggest] parse plain text input for console applications, chat bots or tests. Platform
 * adapters let the platform parse input and call [invoke], so permission checks, cooldowns, translated errors
 * and error handling behave identically everywhere.
 */
public class CommandDispatcher @JvmOverloads constructor(
    translations: Translations = Translations.builder().build(),
    public val argumentTypes: ArgumentTypeRegistry = ArgumentTypeRegistry(),
    clock: Clock = Clock.systemUTC(),
) {
    public val messages: CommandMessages = CommandMessages(translations)
    public val cooldowns: Cooldowns = Cooldowns(clock)
    private val roots = ConcurrentHashMap<String, CommandDefinition>()
    private val labels = ConcurrentHashMap<String, CommandDefinition>()

    /** Sources with this permission skip cooldowns. */
    @Volatile
    public var cooldownBypassPermission: String? = null

    @Volatile
    public var errorHandler: CommandErrorHandler = CommandErrorHandler { source, command, error ->
        System.getLogger(CommandDispatcher::class.java.name).log(
            System.Logger.Level.ERROR,
            "Command '/${command.joinToString(" ") { it.name }}' failed for ${source.name}",
            error,
        )
    }

    /** Registered root commands. */
    public val commands: Collection<CommandDefinition> get() = roots.values.toList()

    /** Registers a root command. Names and aliases must not collide with registered commands. */
    @Synchronized
    public fun register(command: CommandDefinition): CommandDefinition {
        val names = listOf(command.name) + command.aliases
        names.firstOrNull { labels.containsKey(it) }?.let { throw IllegalArgumentException("Command label '$it' is already registered") }
        roots[command.name] = command
        names.forEach { labels[it] = command }
        return command
    }

    /** Registers an instance of a class annotated with `@Command` through its generated descriptor. */
    public fun register(annotated: Any): CommandDefinition =
        register(CommandDescriptor.definitionOf(annotated, argumentTypes))

    @Synchronized
    public fun unregister(name: String): Boolean {
        val command = roots.remove(name) ?: return false
        labels.values.removeIf { it === command }
        return true
    }

    public fun find(label: String): CommandDefinition? = labels[label.lowercase(Locale.ROOT)]

    /** `true` when [source] satisfies the permission of every node in [path]. */
    public fun canUse(source: CommandSource, path: List<CommandDefinition>): Boolean =
        path.all { node -> node.permission?.let(source::hasPermission) ?: true }

    /** Parses and runs [input], for example `coins give Alex 10`. A leading slash is ignored. */
    public fun execute(source: CommandSource, input: String): CommandResult {
        val tokens = tokenize(input.removePrefix("/"))
        val root = tokens.firstOrNull()?.let { find(it.text) }
        if (root == null) {
            messages.send(source, CommandException.UNKNOWN_COMMAND, mapOf("usage" to labelsFor(source)))
            return CommandResult.UNKNOWN_COMMAND
        }
        val path = mutableListOf(root)
        var index = 1
        while (index < tokens.size) {
            path.last().subcommand(tokens[index].text)?.let(path::add) ?: break
            index++
        }
        if (!canUse(source, path)) return deny(source)
        val node = path.last()
        val usage = mapOf("usage" to usage(path))
        if (node.executor == null) {
            messages.send(source, CommandException.USAGE, usage)
            return CommandResult.INVALID_INPUT
        }

        val values = linkedMapOf<String, Any>()
        for (argument in node.arguments) {
            if (index >= tokens.size) {
                if (argument.optional) break
                messages.send(source, CommandException.USAGE, usage)
                return CommandResult.INVALID_INPUT
            }
            val text = if (argument.type.greedy) input.removePrefix("/").substring(tokens[index].start).trim() else tokens[index].text
            index = if (argument.type.greedy) tokens.size else index + 1
            try {
                values[argument.name] = argument.type.parse(text, source)
            } catch (exception: CommandException) {
                messages.send(source, exception.key, mapOf("argument" to argument.name, "input" to text) + exception.placeholders)
                return CommandResult.INVALID_INPUT
            }
        }
        if (index < tokens.size) {
            messages.send(source, CommandException.TOO_MANY_ARGUMENTS, usage)
            return CommandResult.INVALID_INPUT
        }
        return invoke(source, path, values)
    }

    /**
     * Runs the executor of `path.last()` with already parsed [arguments], after checking permissions and the
     * cooldown. All failures are answered with translated messages.
     */
    public fun invoke(source: CommandSource, path: List<CommandDefinition>, arguments: Map<String, Any>): CommandResult {
        require(path.isNotEmpty()) { "Command path must not be empty" }
        if (!canUse(source, path)) return deny(source)
        val node = path.last()
        val executor = node.executor ?: throw IllegalArgumentException("Command '${node.name}' has no executor")
        val bypass = cooldownBypassPermission?.let(source::hasPermission) == true
        val cooldown = node.cooldown?.takeUnless { bypass }
        val cooldownKey = cooldown?.let { "${source.id}:${path.joinToString(" ") { it.name }}" }
        // The cooldown is reserved before running, so concurrent executions cannot both pass the check.
        val reservation = if (cooldown != null && cooldownKey != null) {
            cooldowns.reserve(cooldownKey, cooldown) ?: return cooldownActive(source, cooldownKey)
        } else {
            null
        }
        val result = try {
            executor.execute(CommandContext(source, path.toList(), arguments, messages))
            CommandResult.SUCCESS
        } catch (exception: CommandException) {
            messages.send(source, exception.key, exception.placeholders)
            CommandResult.REJECTED
        } catch (exception: Exception) {
            errorHandler.handle(source, path, exception)
            messages.send(source, CommandException.FAILED, emptyMap())
            CommandResult.ERROR
        }
        // Failed executions do not consume the cooldown.
        if (result != CommandResult.SUCCESS && cooldownKey != null && reservation != null) cooldowns.release(cooldownKey, reservation)
        return result
    }

    private fun cooldownActive(source: CommandSource, key: String): CommandResult {
        val remaining = cooldowns.remaining(key)
        val seconds = remaining.toSeconds() + if (remaining.toMillisPart() > 0) 1 else 0
        val rounded = Duration.ofSeconds(seconds.coerceAtLeast(1))
        messages.send(source, CommandException.COOLDOWN, mapOf("remaining" to Durations.format(rounded)))
        return CommandResult.COOLDOWN
    }

    /** Completions for the last, possibly empty, word of [input]. */
    public fun suggest(source: CommandSource, input: String): List<String> {
        val text = input.removePrefix("/")
        val tokens = tokenize(text)
        val endsWord = text.isNotEmpty() && !text.last().isWhitespace() && tokens.isNotEmpty()
        val partial = if (endsWord) tokens.last().text else ""
        val complete = if (endsWord) tokens.dropLast(1) else tokens
        if (complete.isEmpty()) {
            return filter(roots.values.filter { canUse(source, listOf(it)) }.map { it.name }, partial)
        }
        val path = mutableListOf(find(complete.first().text) ?: return emptyList())
        var index = 1
        while (index < complete.size) {
            path.last().subcommand(complete[index].text)?.let(path::add) ?: break
            index++
        }
        if (!canUse(source, path)) return emptyList()
        val node = path.last()
        if (node.subcommands.isNotEmpty() && index == complete.size) {
            return filter(node.subcommands.filter { canUse(source, path + it) }.map { it.name }, partial)
        }
        val argument = node.arguments.getOrNull(complete.size - path.size) ?: node.arguments.lastOrNull()?.takeIf { it.type.greedy }
            ?: return emptyList()
        return filter(argument.type.suggest(partial, source), partial)
    }

    private fun deny(source: CommandSource): CommandResult {
        messages.send(source, CommandException.NO_PERMISSION, emptyMap())
        return CommandResult.NO_PERMISSION
    }

    private fun labelsFor(source: CommandSource): String =
        roots.values.filter { canUse(source, listOf(it)) }.map { it.name }.sorted().joinToString(", ")

    /** Usage line for the command at the end of [path], for example `/coins give <target> <amount>`. */
    public fun usage(path: List<CommandDefinition>): String =
        "/" + (path.dropLast(1).map { it.name } + path.last().usage()).joinToString(" ")

    private fun filter(candidates: List<String>, partial: String): List<String> =
        candidates.filter { it.startsWith(partial, ignoreCase = true) }.distinct()

    private class Token(val text: String, val start: Int)

    /** Splits on whitespace; double quotes group words and `\"` escapes a quote. */
    private fun tokenize(input: String): List<Token> {
        val tokens = mutableListOf<Token>()
        val current = StringBuilder()
        var start = -1
        var quoted = false
        var index = 0
        while (index < input.length) {
            val char = input[index]
            when {
                char == '\\' && quoted && index + 1 < input.length -> current.append(input[++index])
                char == '"' -> {
                    if (start < 0) start = index
                    quoted = !quoted
                }
                char.isWhitespace() && !quoted -> {
                    if (start >= 0) tokens += Token(current.toString(), start)
                    current.clear()
                    start = -1
                }
                else -> {
                    if (start < 0) start = index
                    current.append(char)
                }
            }
            index++
        }
        if (start >= 0) tokens += Token(current.toString(), start)
        return tokens
    }
}
