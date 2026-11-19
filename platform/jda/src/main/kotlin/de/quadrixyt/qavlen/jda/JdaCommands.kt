package de.quadrixyt.qavlen.jda

import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.BooleanArgument
import de.quadrixyt.qavlen.commands.CommandArgument
import de.quadrixyt.qavlen.commands.CommandDefinition
import de.quadrixyt.qavlen.commands.CommandDescriptor
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.DoubleArgument
import de.quadrixyt.qavlen.commands.EnumArgument
import de.quadrixyt.qavlen.commands.IntegerArgument
import de.quadrixyt.qavlen.commands.LongArgument
import de.quadrixyt.qavlen.commands.StringArgument
import net.dv8tion.jda.api.JDA
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Guild
import net.dv8tion.jda.api.events.interaction.command.CommandAutoCompleteInteractionEvent
import net.dv8tion.jda.api.events.interaction.command.SlashCommandInteractionEvent
import net.dv8tion.jda.api.hooks.ListenerAdapter
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.Command
import net.dv8tion.jda.api.interactions.commands.DefaultMemberPermissions
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType
import net.dv8tion.jda.api.interactions.commands.build.CommandData
import net.dv8tion.jda.api.interactions.commands.build.Commands
import net.dv8tion.jda.api.interactions.commands.build.OptionData
import net.dv8tion.jda.api.interactions.commands.build.SlashCommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandData
import net.dv8tion.jda.api.interactions.commands.build.SubcommandGroupData
import net.dv8tion.jda.api.requests.restaction.CommandListUpdateAction
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.Future
import java.util.concurrent.RejectedExecutionException
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.TimeUnit

/**
 * Runs Qavlen commands as Discord slash commands. Register it as a JDA event listener.
 *
 * A command may have subcommands, and those may have subcommands of their own (Discord subcommand groups), but a
 * node with subcommands cannot be executable itself. Descriptions are translation keys or literal text; every
 * locale of the dispatcher's translations that Discord supports is sent as a localization.
 *
 * Executors run on [executor], not on JDA's event thread. If an executor has not replied after [deferAfter], the
 * interaction is deferred automatically, so slow commands do not hit Discord's three-second response deadline.
 *
 * Discord has no command aliases; aliases remain usable through [CommandDispatcher.execute].
 *
 * Use [syncGlobal] or [syncGuild] to upload commands. Both compare against the registered commands first and only
 * send an update when something changed, which keeps restarts within Discord's command rate limits.
 */
public class JdaCommands @JvmOverloads constructor(
    public val dispatcher: CommandDispatcher,
    private val permissions: DiscordPermissions = DiscordPermissions.GUILD_PERMISSIONS,
    private val executor: ExecutorService = Executors.newVirtualThreadPerTaskExecutor(),
) : ListenerAdapter(), AutoCloseable {
    private val commands = CopyOnWriteArrayList<CommandDefinition>()
    private val timer: ScheduledExecutorService = Executors.newSingleThreadScheduledExecutor { runnable ->
        Thread.ofPlatform().daemon().name("qavlen-jda-defer").unstarted(runnable)
    }

    /** Replies are ephemeral unless set to `false`. Error messages are always ephemeral. */
    @Volatile
    public var ephemeralReplies: Boolean = true

    @Volatile
    private var closed = false

    @Volatile
    public var deferAfter: Duration = Duration.ofMillis(2_000)

    init {
        DiscordArgument.all.forEach { dispatcher.argumentTypes.register(it) }
    }

    /** Registers [command]. Throws [IllegalArgumentException] when it cannot be expressed as a slash command. */
    public fun register(command: CommandDefinition): CommandDefinition {
        slashCommand(command)
        return dispatcher.register(command).also(commands::add)
    }

    /** Registers an instance of an `@Command` class. */
    public fun register(annotated: Any): CommandDefinition =
        register(CommandDescriptor.definitionOf(annotated, dispatcher.argumentTypes))

    /** The slash command data for all registered commands. */
    public fun commandData(): List<SlashCommandData> = commands.map(::slashCommand)

    /** Replaces the global commands if they differ. Completes with `true` when an update was sent. */
    public fun syncGlobal(jda: JDA): CompletableFuture<Boolean> =
        sync(jda.retrieveCommands().submit()) { jda.updateCommands() }

    /** Replaces the commands of [guild] if they differ. Guild commands update instantly, which suits development. */
    public fun syncGuild(guild: Guild): CompletableFuture<Boolean> =
        sync(guild.retrieveCommands().submit()) { guild.updateCommands() }

    private fun sync(remote: CompletableFuture<List<Command>>, update: () -> CommandListUpdateAction): CompletableFuture<Boolean> {
        val local = commandData()
        return remote.thenCompose { existing ->
            if (normalize(existing.filter { it.type == Command.Type.SLASH }.map(CommandData::fromCommand)) == normalize(local)) {
                CompletableFuture.completedFuture(false)
            } else {
                update().addCommands(local).submit().thenApply { true }
            }
        }
    }

    private fun normalize(commands: List<CommandData>): Set<Map<String, Any?>> =
        commands.map { data -> data.toData().toMap().filterKeys { it !in volatileKeys } }.toSet()

    override fun onSlashCommandInteraction(event: SlashCommandInteractionEvent) {
        val path = path(event.name, event.subcommandGroup, event.subcommandName) ?: return
        if (closed) return
        val source = JdaCommandSource(event, permissions, ephemeralReplies, dispatcher.messages.translations.defaultLocale)
        try {
            val deferral = timer.schedule({ source.defer() }, deferAfter.toMillis(), TimeUnit.MILLISECONDS)
            executor.execute { run(source, path, event, deferral) }
        } catch (_: RejectedExecutionException) {
            // Closed while the event was dispatched; the interaction is left to time out like any unhandled one.
        }
    }

    private fun run(source: JdaCommandSource, path: List<CommandDefinition>, event: SlashCommandInteractionEvent, deferral: Future<*>) {
        try {
            val values = linkedMapOf<String, Any>()
            for (argument in path.last().arguments) {
                val option = event.getOption(argument.name) ?: continue
                values[argument.name] = read(argument, option, source)
            }
            dispatcher.invoke(source, path, values)
        } catch (exception: CommandException) {
            dispatcher.messages.send(source, exception.key, exception.placeholders)
        } catch (exception: Exception) {
            dispatcher.errorHandler.handle(source, path, exception)
            dispatcher.messages.send(source, CommandException.FAILED, emptyMap())
        } finally {
            deferral.cancel(false)
            source.finish()
        }
    }

    override fun onCommandAutoCompleteInteraction(event: CommandAutoCompleteInteractionEvent) {
        val path = path(event.name, event.subcommandGroup, event.subcommandName) ?: return
        val focused = event.focusedOption
        val argument = path.last().arguments.firstOrNull { it.name == focused.name } ?: return
        val source = JdaCommandSource(event, permissions, true, dispatcher.messages.translations.defaultLocale)
        if (!dispatcher.canUse(source, path)) return event.replyChoiceStrings(emptyList()).queue()
        val choices = dispatcher.suggestions(argument.type, focused.value, source).take(OptionData.MAX_CHOICES)
        event.replyChoiceStrings(choices).queue()
    }

    /** Stops accepting interactions, waits for running executors and stops the deferral timer. */
    override fun close() {
        closed = true
        executor.close()
        timer.shutdownNow()
    }

    private fun path(name: String, group: String?, subcommand: String?): List<CommandDefinition>? {
        val root = commands.firstOrNull { it.name == name } ?: return null
        val labels = listOfNotNull(group, subcommand)
        val path = mutableListOf(root)
        for (label in labels) path += path.last().subcommands.firstOrNull { it.name == label } ?: return null
        return path
    }

    private fun read(
        argument: CommandArgument<*>,
        option: OptionMapping,
        source: JdaCommandSource,
    ): Any {
        val type = argument.type
        if (type is DiscordArgument<*>) {
            return type.read(option) ?: throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to option.asString))
        }
        return try {
            type.parse(option.asString, source)
        } catch (exception: CommandException) {
            val placeholders = mapOf("argument" to argument.name, "input" to option.asString) + exception.placeholders
            throw CommandException(exception.key, placeholders, exception)
        }
    }

    private fun slashCommand(command: CommandDefinition): SlashCommandData {
        val data = Commands.slash(command.name, description(command.description, command.name))
            .setDescriptionLocalizations(localizations(command.description))
        discordPermission(command.permission)?.let { data.setDefaultPermissions(DefaultMemberPermissions.enabledFor(it)) }
        when {
            command.subcommands.isEmpty() -> data.addOptions(options(command))
            command.executor != null -> throw IllegalArgumentException(
                "Slash command '${command.name}' has subcommands and cannot be executed on its own",
            )
            else -> command.subcommands.forEach { child ->
                if (child.subcommands.isEmpty()) {
                    data.addSubcommands(subcommand(child))
                } else {
                    require(child.executor == null) { "Subcommand group '${command.name} ${child.name}' cannot be executed on its own" }
                    val group = SubcommandGroupData(child.name, description(child.description, child.name))
                        .setDescriptionLocalizations(localizations(child.description))
                    child.subcommands.forEach { leaf ->
                        require(leaf.subcommands.isEmpty()) { "Discord supports at most two levels of subcommands below '${command.name}'" }
                        group.addSubcommands(subcommand(leaf))
                    }
                    data.addSubcommandGroups(group)
                }
            }
        }
        return data
    }

    private fun subcommand(command: CommandDefinition): SubcommandData =
        SubcommandData(command.name, description(command.description, command.name))
            .setDescriptionLocalizations(localizations(command.description))
            .addOptions(options(command))

    private fun options(command: CommandDefinition): List<OptionData> {
        require(command.arguments.size <= MAX_OPTIONS) { "'${command.name}' has more than $MAX_OPTIONS arguments" }
        return command.arguments.map { argument ->
            val type = argument.type
            val option = OptionData(optionType(type), argument.name, description(argument.description, argument.name), !argument.optional)
                .setDescriptionLocalizations(localizations(argument.description))
            when (type) {
                is IntegerArgument -> option.setRequiredRange(
                    type.min.toLong().coerceAtLeast(MIN_SAFE),
                    type.max.toLong().coerceAtMost(MAX_SAFE),
                )
                is LongArgument -> option.setRequiredRange(type.min.coerceAtLeast(MIN_SAFE), type.max.coerceAtMost(MAX_SAFE))
                is DoubleArgument -> option.setRequiredRange(
                    type.min.coerceAtLeast(MIN_SAFE.toDouble()),
                    type.max.coerceAtMost(MAX_SAFE.toDouble()),
                )
                is EnumArgument<*> -> if (type.choices.size <= OptionData.MAX_CHOICES) {
                    type.choices.forEach { option.addChoice(it, it) }
                } else {
                    option.setAutoComplete(true)
                }
                is StringArgument, is BooleanArgument, is DiscordArgument<*> -> Unit
                else -> option.setAutoComplete(true)
            }
            option
        }
    }

    private fun optionType(type: ArgumentType<*>): OptionType = when (type) {
        is DiscordArgument<*> -> type.optionType
        is IntegerArgument, is LongArgument -> OptionType.INTEGER
        is DoubleArgument -> OptionType.NUMBER
        is BooleanArgument -> OptionType.BOOLEAN
        else -> OptionType.STRING
    }

    /** Resolves a description key for the default locale; Discord requires 1-100 characters. */
    private fun description(text: String, fallback: String): String {
        val translations = dispatcher.messages.translations
        val resolved = if (text.isNotEmpty() && translations.contains(translations.defaultLocale, text)) {
            translations.format(translations.defaultLocale, text)
        } else {
            text
        }
        return resolved.ifBlank { fallback }.take(MAX_DESCRIPTION)
    }

    private fun localizations(key: String): Map<DiscordLocale, String> {
        if (key.isEmpty()) return emptyMap()
        return dispatcher.messages.translations.localizations(key)
            .mapKeys { (locale, _) -> DiscordLocale.from(locale) }
            .filterKeys { it != DiscordLocale.UNKNOWN }
            .mapValues { (_, text) -> text.take(MAX_DESCRIPTION) }
    }

    private fun discordPermission(permission: String?): Permission? = Permission.entries.firstOrNull { it.name == permission }

    private companion object {
        const val MAX_DESCRIPTION = 100
        const val MAX_OPTIONS = 25

        // Discord numbers are doubles; integer options are limited to the exactly representable range.
        const val MAX_SAFE = 9_007_199_254_740_991L
        const val MIN_SAFE = -MAX_SAFE

        /** Keys Discord fills in or JDA serializes differently for fetched commands. */
        val volatileKeys = setOf("id", "version", "application_id", "guild_id", "nsfw", "integration_types", "contexts")
    }
}
