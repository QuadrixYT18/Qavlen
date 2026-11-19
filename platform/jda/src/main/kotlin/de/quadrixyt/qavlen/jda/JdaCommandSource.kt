package de.quadrixyt.qavlen.jda

import de.quadrixyt.qavlen.commands.CommandSource
import net.dv8tion.jda.api.Permission
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.interactions.DiscordLocale
import net.dv8tion.jda.api.interactions.Interaction
import net.dv8tion.jda.api.interactions.callbacks.IReplyCallback
import java.util.Locale

/** Decides whether the user of an interaction has a Qavlen permission string. */
public fun interface DiscordPermissions {
    public fun hasPermission(source: JdaCommandSource, permission: String): Boolean

    public companion object {
        /**
         * Treats permission strings as names of JDA [Permission] constants, for example `MANAGE_SERVER`, and checks
         * them against the member's guild permissions. Unknown names and direct messages are denied.
         */
        @JvmField
        public val GUILD_PERMISSIONS: DiscordPermissions = DiscordPermissions { source, permission ->
            val required = Permission.entries.firstOrNull { it.name == permission }
            required != null && source.member?.hasPermission(required) == true
        }
    }
}

/**
 * The user of a slash command or autocomplete interaction. Autocomplete interactions cannot receive messages.
 *
 * Replies are sent as the interaction response while it is not yet acknowledged and as follow-up messages
 * afterwards, so executors can reply several times or after [JdaCommands] deferred the interaction.
 */
public class JdaCommandSource internal constructor(
    public val interaction: Interaction,
    private val permissions: DiscordPermissions,
    private val ephemeral: Boolean,
    private val fallbackLocale: Locale,
) : CommandSource {
    private var replied = false
    private var deferred = false

    public val member: Member? get() = interaction.member

    override val name: String get() = interaction.user.name

    override val id: String get() = interaction.user.id

    override val locale: Locale
        get() = interaction.userLocale.takeIf { it != DiscordLocale.UNKNOWN }?.let { Locale.forLanguageTag(it.locale) } ?: fallbackLocale

    override val handle: Any get() = interaction

    override fun hasPermission(permission: String): Boolean = permissions.hasPermission(this, permission)

    override fun sendMessage(message: String): Unit = reply(message, ephemeral)

    /** Replies with [message], visible only to the user when [ephemeral] is `true`. */
    @Synchronized
    public fun reply(message: String, ephemeral: Boolean) {
        val callback = interaction as? IReplyCallback ?: return
        val text = message.take(MAX_MESSAGE_LENGTH)
        replied = true
        if (callback.isAcknowledged) {
            callback.hook.sendMessage(text).setEphemeral(ephemeral).queue()
        } else {
            callback.reply(text).setEphemeral(ephemeral).queue()
        }
    }

    /** Acknowledges the interaction without a visible reply yet, extending the response window to 15 minutes. */
    @Synchronized
    public fun defer() {
        val callback = interaction as? IReplyCallback ?: return
        if (callback.isAcknowledged) return
        deferred = true
        callback.deferReply(ephemeral).queue()
    }

    /**
     * Completes an interaction that the executor left without a reply, so Discord shows neither an error nor an
     * endless "thinking" state. Responses sent directly through JDA are left untouched.
     */
    @Synchronized
    internal fun finish() {
        val callback = interaction as? IReplyCallback ?: return
        when {
            replied -> Unit
            !callback.isAcknowledged -> callback.deferReply(true).queue { it.deleteOriginal().queue() }
            deferred -> callback.hook.deleteOriginal().queue()
        }
    }

    override fun <T : Any> unwrap(type: Class<T>): T? =
        listOfNotNull(interaction, interaction.member, interaction.user, interaction.guild, interaction.messageChannel)
            .firstOrNull(type::isInstance)?.let(type::cast)

    private companion object {
        const val MAX_MESSAGE_LENGTH = 2000
    }
}
