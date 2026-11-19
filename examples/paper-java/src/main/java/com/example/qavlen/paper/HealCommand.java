package com.example.qavlen.paper;

import de.quadrixyt.qavlen.adventure.AdventureMessages;
import de.quadrixyt.qavlen.commands.CommandException;
import de.quadrixyt.qavlen.commands.annotations.Command;
import de.quadrixyt.qavlen.commands.annotations.Execute;
import de.quadrixyt.qavlen.commands.annotations.Optional;
import de.quadrixyt.qavlen.commands.annotations.Permission;
import de.quadrixyt.qavlen.commands.annotations.Sender;
import de.quadrixyt.qavlen.language.Placeholder;
import org.bukkit.attribute.Attribute;
import org.bukkit.command.CommandSender;
import org.bukkit.entity.Player;

import java.util.Map;

@Command(name = "heal", description = "Heals a player")
@Permission("example.heal")
public final class HealCommand {
    private final AdventureMessages messages;

    public HealCommand(AdventureMessages messages) {
        this.messages = messages;
    }

    @Execute
    public void heal(@Sender CommandSender sender, @Optional Player target) {
        Player player = target != null ? target : sender instanceof Player self ? self : null;
        if (player == null) {
            throw new CommandException(CommandException.USAGE, Map.of("usage", "/heal <player>"));
        }
        var maxHealth = player.getAttribute(Attribute.MAX_HEALTH);
        player.setHealth(maxHealth != null ? maxHealth.getValue() : 20.0);
        messages.send(sender, "heal.done", Placeholder.of("player", player.getName()));
    }
}
