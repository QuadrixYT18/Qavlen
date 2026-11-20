package com.example.qavlen.paper;

import de.quadrixyt.qavlen.commands.CommandDispatcher;
import de.quadrixyt.qavlen.config.ConfigOptions;
import de.quadrixyt.qavlen.config.YamlConfig;
import de.quadrixyt.qavlen.language.Placeholder;
import de.quadrixyt.qavlen.language.Translations;
import de.quadrixyt.qavlen.paper.Events;
import de.quadrixyt.qavlen.paper.PaperCommands;
import de.quadrixyt.qavlen.updater.UpdateChecker;
import de.quadrixyt.qavlen.updater.UpdateOptions;
import de.quadrixyt.qavlen.updater.UpdateResult;
import org.bukkit.entity.Player;
import org.bukkit.event.player.PlayerJoinEvent;
import org.bukkit.plugin.java.JavaPlugin;

import java.util.Locale;
import java.util.Map;

public final class ExamplePlugin extends JavaPlugin {
    private UpdateChecker updates;

    @Override
    public void onEnable() {
        updates = UpdateChecker.hangar("ExampleAuthor/ExamplePlugin", getPluginMeta().getVersion(),
            UpdateOptions.builder().platform("paper").build());
        updates.checkAsync().thenAccept(result -> {
            if (result instanceof UpdateResult.Available update) {
                getLogger().info("Version " + update.getLatestVersion() + " is available: " + update.getReleaseUrl());
            } else if (result instanceof UpdateResult.Failed failed) {
                getLogger().warning("Update check failed: " + failed.getReason());
            }
        });

        Translations translations = Translations.builder()
            .messages(Locale.ENGLISH, Map.of(
                "heal.done", "<green>Healed <white>{player}</white>.",
                "welcome", "<gray>Welcome, <white>{player}</white>!"))
            .directory(getDataPath().resolve("lang"))
            .build();
        // MiniMessage texts can also live in the configuration; getComponent renders them with safe placeholders.
        YamlConfig config = YamlConfig.load(getDataPath().resolve("config.yml"), ConfigOptions.builder()
            .defaults(Map.of("first-join", "<gold>{player} joined for the first time!"))
            .build());
        PaperCommands commands = new PaperCommands(this, new CommandDispatcher(translations));
        commands.register(new HealCommand(commands.getMessages()));

        Events.listen(this, PlayerJoinEvent.class, event -> {
            Player player = event.getPlayer();
            commands.getMessages().send(player, "welcome", Placeholder.of("player", player.getName()));
            if (!player.hasPlayedBefore()) {
                getServer().broadcast(config.getComponent("first-join", Map.of("player", player.getName())));
            }
        });
    }

    @Override
    public void onDisable() {
        if (updates != null) {
            updates.close();
        }
    }
}
