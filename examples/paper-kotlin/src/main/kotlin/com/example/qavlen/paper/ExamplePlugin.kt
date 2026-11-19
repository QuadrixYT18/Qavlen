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
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.java.JavaPlugin
import java.util.Locale

class ExamplePlugin : JavaPlugin() {
    override fun onEnable() {
        val config = YamlConfig.load(
            dataPath.resolve("config.yml"),
            ConfigOptions { defaults(mapOf("coins" to mapOf("starting-balance" to 100))) },
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
    }
}
