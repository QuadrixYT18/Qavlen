package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.language.Placeholder
import de.quadrixyt.qavlen.language.Translations
import java.util.Locale

/**
 * Everything an executor needs: who ran the command, which node was reached and the parsed arguments.
 *
 * @property path command nodes from the root to the executed node
 */
public class CommandContext(
    public val source: CommandSource,
    public val path: List<CommandDefinition>,
    private val arguments: Map<String, Any>,
    private val messages: CommandMessages,
) {
    public val command: CommandDefinition get() = path.last()

    public val locale: Locale get() = source.locale

    public val translations: Translations get() = messages.translations

    public fun has(name: String): Boolean = name in arguments

    /**
     * Returns a parsed argument.
     *
     * @throws IllegalArgumentException when the argument is absent or has a different type
     */
    public fun <T : Any> argument(name: String, type: Class<T>): T =
        argumentOrNull(name, type) ?: throw IllegalArgumentException("Argument '$name' was not provided")

    /** Returns a parsed argument, or `null` for an omitted optional argument. */
    public fun <T : Any> argumentOrNull(name: String, type: Class<T>): T? {
        val value = arguments[name] ?: return null
        val boxed = type.kotlin.javaObjectType
        require(boxed.isInstance(value)) { "Argument '$name' is ${value.javaClass.name}, not ${type.name}" }
        return boxed.cast(value)
    }

    /**
     * Returns the source as [type], for example a player.
     *
     * @throws CommandException with [CommandException.SENDER_TYPE] when the source is of another kind
     */
    public fun <T : Any> sender(type: Class<T>): T = source.unwrap(type)
        ?: throw CommandException(CommandException.SENDER_TYPE, mapOf("type" to type.simpleName.lowercase(Locale.ROOT)))

    public fun reply(message: String): Unit = source.sendMessage(message)

    /** Sends a translated message using the dispatcher's translations with the built-in Qavlen messages as fallback. */
    public fun replyTranslated(key: String, vararg placeholders: Placeholder) {
        messages.send(source, key, placeholders.associate { it.name to it.value })
    }

    public fun replyTranslated(key: String, placeholders: Map<String, Any?>) {
        messages.send(source, key, placeholders)
    }

    /** Formats a translated message for this source without sending it. */
    public fun translate(key: String, vararg placeholders: Placeholder): String =
        messages.format(locale, key, placeholders.associate { it.name to it.value })
}

/** Kotlin shorthand for [CommandContext.argument]. */
public inline fun <reified T : Any> CommandContext.argument(name: String): T = argument(name, T::class.java)

/** Kotlin shorthand for [CommandContext.argumentOrNull]. */
public inline fun <reified T : Any> CommandContext.argumentOrNull(name: String): T? = argumentOrNull(name, T::class.java)

/** Kotlin shorthand for [CommandContext.sender]. */
public inline fun <reified T : Any> CommandContext.sender(): T = sender(T::class.java)

/** Resolves translation keys against user translations first and Qavlen's bundled command messages second. */
public class CommandMessages(public val translations: Translations) {
    public fun format(locale: Locale, key: String, placeholders: Map<String, Any?>): String =
        source(locale, key).format(locale, key, placeholders)

    public fun send(source: CommandSource, key: String, placeholders: Map<String, Any?>) {
        source.sendTranslated(source(source.locale, key), key, placeholders)
    }

    /** The [Translations] that define [key] for [locale]. */
    public fun source(locale: Locale, key: String): Translations =
        if (translations.contains(locale, key) || !bundled.contains(locale, key)) translations else bundled

    public companion object {
        /** Qavlen's own English and German command messages in the `qavlen` namespace. */
        @JvmStatic
        public val bundled: Translations = Translations.builder()
            .resources(CommandMessages::class.java.classLoader, "qavlen/lang", listOf(Locale.ENGLISH, Locale.GERMAN), "qavlen")
            .build()
    }
}
