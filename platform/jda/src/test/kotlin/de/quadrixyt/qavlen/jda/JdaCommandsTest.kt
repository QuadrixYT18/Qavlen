package de.quadrixyt.qavlen.jda

import de.quadrixyt.qavlen.commands.ArgumentTypes
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.command
import de.quadrixyt.qavlen.language.Translations
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.commands.OptionType
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class JdaCommandsTest {
    enum class Color { RED, GREEN }

    private val translations = Translations.builder()
        .messages(Locale.ENGLISH, mapOf("mod.description" to "Moderation tools", "mod.reason" to "Why"))
        .messages(Locale.GERMAN, mapOf("mod.description" to "Moderationswerkzeuge", "mod.reason" to "Warum"))
        .build()

    @Test
    fun `maps definitions to slash commands`() {
        JdaCommands(CommandDispatcher(translations)).use { commands ->
            commands.register(
                command("mod") { root ->
                    root.description("mod.description").permission("BAN_MEMBERS")
                    root.subcommand("ban") {
                        it.argument("target", DiscordArgument.all.first { type -> type.valueType == Member::class.java })
                            .argument("days", ArgumentTypes.integer(0, 7))
                            .optionalArgument("reason", ArgumentTypes.greedyString(), "mod.reason")
                            .executes {}
                    }
                    root.subcommand("config") { group ->
                        group.subcommand("color") { it.argument("color", ArgumentTypes.enumeration(Color::class.java)).executes {} }
                    }
                },
            )

            val data = commands.commandData().single()
            assertEquals("Moderation tools", data.description)
            assertEquals("Moderationswerkzeuge", data.descriptionLocalizations.toMap()[DiscordLocale.GERMAN])
            assertEquals(Permission.getRaw(Permission.BAN_MEMBERS), data.defaultPermissions.permissionsRaw)

            val ban = data.subcommands.single()
            assertEquals(listOf(OptionType.USER, OptionType.INTEGER, OptionType.STRING), ban.options.map { it.type })
            assertEquals(listOf(true, true, false), ban.options.map { it.isRequired })
            assertEquals(7, ban.options[1].maxValue?.toInt())
            assertEquals("Warum", ban.options[2].descriptionLocalizations.toMap()[DiscordLocale.GERMAN])

            val color = data.subcommandGroups.single().subcommands.single().options.single()
            assertEquals(listOf("red", "green"), color.choices.map { it.asString })
        }
    }

    @Test
    fun `rejects structures discord cannot express`() {
        JdaCommands(CommandDispatcher()).use { commands ->
            assertFailsWith<IllegalArgumentException> {
                commands.register(command("a") { it.executes {}.subcommand("b") { b -> b.executes {} } })
            }
            assertFailsWith<IllegalArgumentException> {
                commands.register(
                    command("a") { a -> a.subcommand("b") { b -> b.subcommand("c") { c -> c.subcommand("d") { d -> d.executes {} } } } },
                )
            }
            assertTrue(commands.dispatcher.commands.isEmpty())
        }
    }
}
