package com.example.qavlen.jda

import de.quadrixyt.qavlen.commands.CommandContext
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.annotations.Arg
import de.quadrixyt.qavlen.commands.annotations.Command
import de.quadrixyt.qavlen.commands.annotations.Execute
import de.quadrixyt.qavlen.commands.annotations.Greedy
import de.quadrixyt.qavlen.commands.annotations.Permission
import de.quadrixyt.qavlen.commands.annotations.Subcommand
import de.quadrixyt.qavlen.jda.JdaCommands
import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.language.placeholder
import net.dv8tion.jda.api.JDABuilder
import net.dv8tion.jda.api.entities.Role
import java.util.Locale
import java.util.concurrent.atomic.AtomicInteger

@Command("poll", description = "poll.description")
class PollCommand {
    private val open = AtomicInteger()

    @Subcommand("create", description = "poll.create.description")
    fun create(context: CommandContext, @Greedy @Arg(description = "poll.question") question: String) {
        open.incrementAndGet()
        context.replyTranslated("poll.created", placeholder("question", question), placeholder("author", context.source.name))
    }

    @Subcommand("close", description = "poll.close.description")
    @Permission("MANAGE_MESSAGES")
    fun close(context: CommandContext) {
        context.replyTranslated("poll.closed", placeholder("count", open.getAndSet(0)))
    }
}

@Command("role", description = "roles.description")
class RoleCommand {
    @Execute
    fun info(context: CommandContext, role: Role) {
        context.replyTranslated("roles.info", placeholder("role", role.name), placeholder("members", role.guild.getMembersWithRoles(role).size))
    }
}

fun createCommands(): JdaCommands {
    val translations = Translations.builder()
        .resources(PollCommand::class.java.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN))
        .build()
    return JdaCommands(CommandDispatcher(translations)).apply {
        register(PollCommand())
        register(RoleCommand())
    }
}

fun main() {
    val token = System.getenv("DISCORD_TOKEN") ?: error("Set DISCORD_TOKEN to run the example bot")
    val commands = createCommands()
    val jda = JDABuilder.createLight(token).addEventListeners(commands).build().awaitReady()
    val guild = System.getenv("DISCORD_GUILD")?.let(jda::getGuildById)
    val updated = if (guild != null) commands.syncGuild(guild).join() else commands.syncGlobal(jda).join()
    println(if (updated) "Slash commands updated" else "Slash commands unchanged")
}
