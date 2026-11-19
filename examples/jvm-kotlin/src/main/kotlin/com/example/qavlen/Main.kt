package com.example.qavlen

import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandSource
import de.quadrixyt.qavlen.config.ConfigOptions
import de.quadrixyt.qavlen.config.YamlConfig
import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.serialization.ConfigBinding
import de.quadrixyt.qavlen.serialization.bind
import java.nio.file.Path
import java.util.Locale

class ConsoleSource(override val locale: Locale) : CommandSource {
    override val name: String get() = "console"
    override val id: String get() = "console"
    override val handle: Any get() = this

    override fun hasPermission(permission: String): Boolean = true

    override fun sendMessage(message: String) = println(message)
}

data class BankSettings(val startingBalance: Long = 100, val locale: String = "en")

fun createDispatcher(config: YamlConfig): CommandDispatcher {
    val translations = Translations.builder()
        .defaultLocale(Locale.ENGLISH)
        .resources(Accounts::class.java.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN))
        .build()
    val dispatcher = CommandDispatcher(translations)
    val settings = config.bind<BankSettings>("bank")
    dispatcher.register(BankCommand(Accounts(settings.startingBalance)))
    return dispatcher
}

fun main() {
    val options = ConfigOptions { defaults(mapOf("bank" to ConfigBinding.toMap(BankSettings()))) }
    val config = YamlConfig.load(Path.of("bank.yml"), options)
    val dispatcher = createDispatcher(config)
    val source = ConsoleSource(Locale.forLanguageTag(config.bind<BankSettings>("bank").locale))

    println("Type a command such as 'bank deposit 50' or 'exit'.")
    generateSequence(::readlnOrNull).takeWhile { it.trim() != "exit" }.filter(String::isNotBlank).forEach { line ->
        dispatcher.execute(source, line)
    }
}
