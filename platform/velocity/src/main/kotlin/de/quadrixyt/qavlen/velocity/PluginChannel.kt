package de.quadrixyt.qavlen.velocity

import com.velocitypowered.api.event.EventHandler
import com.velocitypowered.api.event.connection.PluginMessageEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.ServerConnection
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import java.util.function.BiConsumer

/**
 * A plugin messaging channel between the proxy and backend servers.
 *
 * Only messages sent by a backend server are passed to the handler. Messages on this channel that come from a
 * player's client are dropped and never forwarded, because clients can send arbitrary data on any channel.
 * Closing the channel unregisters it.
 */
public class PluginChannel(
    private val server: ProxyServer,
    private val plugin: Any,
    public val identifier: MinecraftChannelIdentifier,
    handler: BiConsumer<ServerConnection, ByteArray>,
) : AutoCloseable {
    private val listener = EventHandler<PluginMessageEvent> { event ->
        if (event.identifier != identifier) return@EventHandler
        event.result = PluginMessageEvent.ForwardResult.handled()
        val source = event.source
        if (source is ServerConnection) handler.accept(source, event.data.copyOf())
    }

    init {
        server.channelRegistrar.register(identifier)
        server.eventManager.register(plugin, PluginMessageEvent::class.java, listener)
    }

    /** Sends to the backend server the player is connected to, through that player's connection. */
    public fun send(player: Player, data: ByteArray): Boolean =
        player.currentServer.map { it.sendPluginMessage(identifier, data) }.orElse(false)

    /** Sends to a backend server. Backend servers only receive it while at least one player is connected there. */
    public fun send(target: RegisteredServer, data: ByteArray): Boolean = target.sendPluginMessage(identifier, data)

    override fun close() {
        server.eventManager.unregister(plugin, listener)
        server.channelRegistrar.unregister(identifier)
    }
}
