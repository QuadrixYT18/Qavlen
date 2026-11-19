package com.example.qavlen.velocity;

import com.google.inject.Inject;
import com.velocitypowered.api.event.Subscribe;
import com.velocitypowered.api.event.proxy.ProxyInitializeEvent;
import com.velocitypowered.api.plugin.Plugin;
import com.velocitypowered.api.proxy.Player;
import com.velocitypowered.api.proxy.ProxyServer;
import de.quadrixyt.qavlen.commands.CommandContext;
import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.commands.annotations.Command;
import de.quadrixyt.qavlen.commands.annotations.Execute;
import de.quadrixyt.qavlen.velocity.VelocityCommands;

@Plugin(id = "qavlen-java-example", name = "QavlenJavaExample", version = "1.0.0")
public final class ExamplePlugin {
    private final ProxyServer server;

    @Inject
    public ExamplePlugin(ProxyServer server) {
        this.server = server;
    }

    @Subscribe
    public void onInitialize(ProxyInitializeEvent event) {
        VelocityCommands commands = new VelocityCommands(server, this, new CommandDispatcher());
        commands.register(new OnlineCommand(server));
    }

    @Command(name = "online")
    public static final class OnlineCommand {
        private final ProxyServer server;

        public OnlineCommand(ProxyServer server) {
            this.server = server;
        }

        @Execute
        public void online(CommandContext context) {
            context.reply(server.getPlayerCount() + " players: "
                + String.join(", ", server.getAllPlayers().stream().map(Player::getUsername).toList()));
        }
    }
}
