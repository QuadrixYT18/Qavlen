package com.example.qavlen.paper

import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.config.ConfigOptions
import de.quadrixyt.qavlen.config.YamlConfig
import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.paper.Menus
import de.quadrixyt.qavlen.paper.PaperCommands
import de.quadrixyt.qavlen.paper.PaperScheduler
import de.quadrixyt.qavlen.paper.PersistentKey
import de.quadrixyt.qavlen.paper.listen
import de.quadrixyt.qavlen.updater.UpdateChecker
import de.quadrixyt.qavlen.updater.UpdateResult
import org.bukkit.event.player.PlayerJoinEvent
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

class ExamplePlugin : JavaPlugin() {
    private lateinit var updates: UpdateChecker

    override fun onEnable() {
        // Runs on an HTTP thread; logging is thread-safe, game objects would need the scheduler.
        updates = UpdateChecker.modrinth("example-plugin", pluginMeta.version) { platform("paper") }
        updates.checkAsync().thenAccept { result ->
            when (result) {
                is UpdateResult.Available -> logger.info("Version ${result.latestVersion} is available: ${result.releaseUrl}")
                is UpdateResult.UpToDate -> logger.info("Running the latest version")
                is UpdateResult.Failed -> logger.warning("Update check failed: ${result.reason}")
            }
        }

        val config = YamlConfig.load(
            dataPath.resolve("config.yml"),
            ConfigOptions {
                defaults(
                    mapOf(
                        "coins" to mapOf("starting-balance" to 100),
                        "prefix" to "<gray>[Example]</gray>",
                        // MiniMessage from the config; {player} is inserted as plain text and cannot inject tags.
                        "join-message" to "<prefix> <gradient:gold:yellow>Welcome, {player}!</gradient>",
                    ),
                )
            },
        )
        val translations = Translations.builder()
            .defaultLocale(Locale.ENGLISH)
            .resources(javaClass.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN))
            .directory(dataPath.resolve("lang"))
            .build()
        val messages = AdventureMessages(translations)
        val commands = PaperCommands(this, CommandDispatcher(translations), messages)
        val coins = PersistentKey(this, "coins", PersistentDataType.LONG)

        Menus.install(this)
        val scheduler = PaperScheduler(this)
        commands.register(CoinsCommand(coins, scheduler, messages, config.getLong("coins.starting-balance", 100)))
        commands.register(PlayersCommand(messages))
        commands.register(SpawnCommand(scheduler, messages))
        listen<PlayerJoinEvent> { event ->
            event.joinMessage(config.getComponent("join-message", "player" to event.player.name))
        }
    }

    override fun onDisable() {
        if (::updates.isInitialized) updates.close()
    }
}
