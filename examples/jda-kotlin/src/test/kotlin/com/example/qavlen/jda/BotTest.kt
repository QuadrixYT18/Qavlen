package com.example.qavlen.jda

import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import kotlin.test.Test
import kotlin.test.assertEquals

class BotTest {
    @Test
    fun `annotated commands become localized slash commands`() {
        createCommands().use { commands ->
            val data = commands.commandData().associateBy { it.name }
            val poll = data.getValue("poll")

            assertEquals("Umfragen erstellen und schließen", poll.descriptionLocalizations.toMap()[DiscordLocale.GERMAN])
            assertEquals(listOf("create", "close"), poll.subcommands.map { it.name })
            assertEquals("The question to ask", poll.subcommands.first().options.single().description)
            assertEquals(OptionType.ROLE, data.getValue("role").options.single().type)
        }
    }
}
