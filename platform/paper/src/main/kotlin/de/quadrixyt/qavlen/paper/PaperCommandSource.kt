package de.quadrixyt.qavlen.paper

import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.CommandSource
import de.quadrixyt.qavlen.language.Translations
import io.papermc.paper.command.brigadier.CommandSourceStack
import net.kyori.adventure.text.Component
import org.bukkit.Server
import org.bukkit.World
import org.bukkit.command.CommandSender
import org.bukkit.entity.Entity
import org.bukkit.entity.Player
import java.util.Locale

/**
 * A Paper command source. Messages are rendered with MiniMessage; the locale is the player's client locale.
 * [unwrap] returns the executing entity first (which `/execute as` may change), then the sender.
 */
public class PaperCommandSource(public val stack: CommandSourceStack, private val messages: AdventureMessages) : CommandSource {
    public val sender: CommandSender get() = stack.sender

    override val name: String get() = sender.name

    override val id: String get() = (sender as? Entity)?.uniqueId?.toString() ?: sender.name

    override val locale: Locale get() = (sender as? Player)?.locale() ?: messages.translations.defaultLocale

    override val handle: Any get() = stack

    override fun hasPermission(permission: String): Boolean = sender.hasPermission(permission)

    override fun sendMessage(message: String) {
        sender.sendMessage(Component.text(message))
    }

    override fun sendTranslated(translations: Translations, key: String, placeholders: Map<String, Any?>) {
        val renderer = if (translations === messages.translations) messages else AdventureMessages(translations)
        sender.sendMessage(renderer.render(locale, key, placeholders))
    }

    override fun <T : Any> unwrap(type: Class<T>): T? = listOfNotNull(
        stack.executor,
        sender,
        stack,
    ).firstOrNull(type::isInstance)?.let(type::cast)
}

/** An online player, matched by exact name when parsed as text. On Paper it maps to the native player selector. */
public class PlayerArgument(private val server: Server) : ArgumentType<Player> {
    override val valueType: Class<Player> get() = Player::class.java

    override fun parse(input: String, source: CommandSource): Player = server.getPlayerExact(input)
        ?: throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to input))

    override fun suggest(input: String, source: CommandSource): List<String> = server.onlinePlayers.map(Player::getName)
}

/** A loaded world by name. On Paper it maps to the native world argument. */
public class WorldArgument(private val server: Server) : ArgumentType<World> {
    override val valueType: Class<World> get() = World::class.java

    override fun parse(input: String, source: CommandSource): World = server.getWorld(input)
        ?: throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to input))

    override fun suggest(input: String, source: CommandSource): List<String> = server.worlds.map(World::getName)
}
