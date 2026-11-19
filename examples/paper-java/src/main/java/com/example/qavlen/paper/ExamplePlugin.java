package com.example.qavlen.paper;

import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.language.Placeholder;
import de.quadrixyt.qavlen.language.Translations;
import de.quadrixyt.qavlen.paper.Events;
import de.quadrixyt.qavlen.paper.PaperCommands;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;

public final class ExamplePlugin extends JavaPlugin {
    @Override
    public void onEnable() {
        Translations translations = Translations.builder()
            .messages(Locale.ENGLISH, Map.of(
                "heal.done", "<green>Healed <white>{player}</white>.",
                "welcome", "<gray>Welcome, <white>{player}</white>!"))
            .directory(getDataPath().resolve("lang"))
            .build();
        PaperCommands commands = new PaperCommands(this, new CommandDispatcher(translations));
        commands.register(new HealCommand(commands.getMessages()));

        Events.listen(this, PlayerJoinEvent.class, event ->
            commands.getMessages().send(event.getPlayer(), "welcome",
                Placeholder.of("player", event.getPlayer().getName())));
    }
}
