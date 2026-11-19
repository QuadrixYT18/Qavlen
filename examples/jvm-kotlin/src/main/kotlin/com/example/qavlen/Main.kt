package com.example.qavlen

import de.quadrixyt.qavlen.commands.CommandDispatcher
import de.quadrixyt.qavlen.commands.CommandSource
import de.quadrixyt.qavlen.language.Translations
import de.quadrixyt.qavlen.serialization.Comment
import de.quadrixyt.qavlen.serialization.Configs
import de.quadrixyt.qavlen.serialization.Configuration
import de.quadrixyt.qavlen.serialization.Pattern
import de.quadrixyt.qavlen.serialization.Range
import java.nio.file.Path
import java.util.Locale

class ConsoleSource(override val locale: Locale) : CommandSource {
    override val name: String get() = "console"
    override val id: String get() = "console"
    override val handle: Any get() = this

    override fun hasPermission(permission: String): Boolean = true

    override fun sendMessage(message: String) = println(message)
}

@Configuration("Configuration of the example bank")
data class BankConfig(
    @Comment("Coins every new account starts with")
    @Range(min = 0.0)
    val startingBalance: Long = 100,
    @Comment("Language of the console: en or de")
    @Pattern("en|de")
    val locale: String = "en",
)

fun createDispatcher(config: BankConfig): CommandDispatcher {
    val translations = Translations.builder()
        .defaultLocale(Locale.ENGLISH)
        .resources(Accounts::class.java.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN))
        .build()
    val dispatcher = CommandDispatcher(translations)
    dispatcher.register(BankCommand(Accounts(config.startingBalance)))
    return dispatcher
}

fun main() {
    // Created with comments on the first run; BANK_STARTING_BALANCE overrides the file without changing it.
    val config = Configs.open<BankConfig>(Path.of("bank.yml")) { environment("BANK") }
    val dispatcher = createDispatcher(config.value)
    val source = ConsoleSource(Locale.forLanguageTag(config.value.locale))

    println("Type a command such as 'bank deposit 50' or 'exit'.")
    generateSequence(::readlnOrNull).takeWhile { it.trim() != "exit" }.filter(String::isNotBlank).forEach { line ->
        dispatcher.execute(source, line)
    }
}
