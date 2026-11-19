package de.quadrixyt.qavlen.paper

import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger
import org.bukkit.plugin.messaging.PluginMessageListener
import java.util.function.BiConsumer

/**
 * A plugin messaging channel between this server and the proxy, the counterpart of the Velocity module's
 * `PluginChannel`.
 *
 * Plugin messages always travel through a player's connection, so a player must be online to send or receive.
 * The backend cannot tell whether a message was written by the proxy or by the player's client. Only trust
 * messages when the proxy drops client messages on this channel, as Qavlen's Velocity `PluginChannel` does,
 * and keep the server unreachable except through the proxy.
 *
 * The handler runs on the thread that processes the player's connection; on Folia, use [PaperScheduler] before
 * touching the world. Closing the channel unregisters it.
 *
 * @param channel `namespace:name` in lowercase, for example `myplugin:sync`
 */
public class PluginChannel(
    private val plugin: Plugin,
    public val channel: String,
    handler: BiConsumer<Player, ByteArray>,
) : AutoCloseable {
    private val listener = PluginMessageListener { received, player, data ->
        if (received == channel) handler.accept(player, data.copyOf())
    }

    init {
        require(NAME.matches(channel) && channel.length <= Messenger.MAX_CHANNEL_SIZE) {
            "Channel '$channel' must be 'namespace:name' using lowercase letters, digits, '.', '_', '-' and '/'"
        }
        val messenger = plugin.server.messenger
        require(!messenger.isReservedChannel(channel)) { "Channel '$channel' is reserved" }
        messenger.registerOutgoingPluginChannel(plugin, channel)
        messenger.registerIncomingPluginChannel(plugin, channel, listener)
    }

    /** Sends [data] to the proxy through [player]'s connection. */
    public fun send(player: Player, data: ByteArray) {
        require(data.size <= Messenger.MAX_MESSAGE_SIZE) { "Plugin messages are limited to ${Messenger.MAX_MESSAGE_SIZE} bytes" }
        player.sendPluginMessage(plugin, channel, data)
    }

    /**
     * Sends [data] through any online player, for messages that are not about a specific player.
     *
     * @return `false` when nobody is online and the message could not be sent
     */
    public fun send(data: ByteArray): Boolean {
        val player = plugin.server.onlinePlayers.firstOrNull() ?: return false
        send(player, data)
        return true
    }

    override fun close() {
        val messenger = plugin.server.messenger
        messenger.unregisterIncomingPluginChannel(plugin, channel, listener)
        messenger.unregisterOutgoingPluginChannel(plugin, channel)
    }

    private companion object {
        val NAME = Regex("[a-z0-9._-]+:[a-z0-9._/-]+")
    }
}
