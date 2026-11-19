package de.quadrixyt.qavlen.jda

import de.quadrixyt.qavlen.commands.ArgumentType
import de.quadrixyt.qavlen.commands.CommandException
import de.quadrixyt.qavlen.commands.CommandSource
import net.dv8tion.jda.api.entities.Member
import net.dv8tion.jda.api.entities.Message
import net.dv8tion.jda.api.entities.Role
import net.dv8tion.jda.api.entities.User
import net.dv8tion.jda.api.entities.channel.middleman.GuildChannel
import net.dv8tion.jda.api.interactions.commands.OptionMapping
import net.dv8tion.jda.api.interactions.commands.OptionType

/**
 * A Discord entity option. Slash commands deliver these values already resolved by Discord, so they cannot be
 * parsed from text and are only usable with [JdaCommands].
 */
public sealed class DiscordArgument<T : Any>(
    override val valueType: Class<T>,
    internal val optionType: OptionType,
) : ArgumentType<T> {
    internal abstract fun read(option: OptionMapping): T?

    override fun parse(input: String, source: CommandSource): T =
        throw CommandException(CommandException.INVALID_ARGUMENT, mapOf("input" to input))

    internal object Users : DiscordArgument<User>(User::class.java, OptionType.USER) {
        override fun read(option: OptionMapping): User = option.asUser
    }

    /** Resolves to `null` outside guilds or when the user is not a member, which is reported as an invalid argument. */
    internal object Members : DiscordArgument<Member>(Member::class.java, OptionType.USER) {
        override fun read(option: OptionMapping): Member? = option.asMember
    }

    internal object Roles : DiscordArgument<Role>(Role::class.java, OptionType.ROLE) {
        override fun read(option: OptionMapping): Role = option.asRole
    }

    internal object Channels : DiscordArgument<GuildChannel>(GuildChannel::class.java, OptionType.CHANNEL) {
        override fun read(option: OptionMapping): GuildChannel = option.asChannel
    }

    internal object Attachments : DiscordArgument<Message.Attachment>(Message.Attachment::class.java, OptionType.ATTACHMENT) {
        override fun read(option: OptionMapping): Message.Attachment = option.asAttachment
    }

    public companion object {
        /** All Discord entity types, registered automatically by [JdaCommands]. */
        @JvmStatic
        public val all: List<DiscordArgument<*>> get() = listOf(Users, Members, Roles, Channels, Attachments)
    }
}
