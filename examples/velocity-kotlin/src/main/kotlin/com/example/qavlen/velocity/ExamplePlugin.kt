package com.example.qavlen.velocity

import com.google.inject.Inject
import com.velocitypowered.api.event.Subscribe
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent
import com.velocitypowered.api.proxy.Player
import com.velocitypowered.api.proxy.ProxyServer
import com.velocitypowered.api.proxy.messages.MinecraftChannelIdentifier
import com.velocitypowered.api.proxy.server.RegisteredServer
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.annotations.Command
import de.quadrixyt.qavlen.commands.annotations.Execute
import de.quadrixyt.qavlen.commands.annotations.Sender
import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.language.placeholder
import de.quadrixyt.qavlen.velocity.PluginChannel
import de.quadrixyt.qavlen.velocity.VelocityCommands
import java.util.Locale

class ExamplePlugin @Inject constructor(private val server: ProxyServer) {
    private lateinit var channel: PluginChannel

    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val translations = Translations.builder()
            .messages(
                Locale.ENGLISH,
                mapOf(
                    "send.connecting" to "<gray>Connecting you to <white>{server}</white>…",
                    "send.failed" to "<red>Could not connect to {server}.",
                ),
            )
            .build()
        val commands = VelocityCommands(server, this, CommandDispatcher(translations))
        commands.register(SendCommand(commands.messages))
        channel = PluginChannel(server, this, MinecraftChannelIdentifier.create("example", "main")) { connection, data ->
            server.consoleCommandSource.sendPlainMessage("${connection.serverInfo.name} sent ${data.size} bytes")
        }
    }
}

@Command("hub", aliases = ["lobby"])
class SendCommand(private val messages: AdventureMessages) {
    @Execute
    fun connect(@Sender player: Player, target: RegisteredServer) {
        val name = placeholder("server", target.serverInfo.name)
        messages.send(player, "send.connecting", name)
        player.createConnectionRequest(target).connect().thenAccept { result ->
            if (!result.isSuccessful) messages.send(player, "send.failed", name)
        }
    }
}
