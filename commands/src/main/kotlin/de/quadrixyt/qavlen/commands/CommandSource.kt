package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.language.Translations
import java.util.Locale

/**
 * Whoever executes a command: a player, the console, a Discord user. Platform adapters implement this around
 * their native sender and expose it as [handle].
 */
public interface CommandSource {
    /** Display name used in messages. */
    public val name: String

    /** Stable identity used for cooldowns, for example a UUID or Discord user id. */
    public val id: String

    /** Locale for translated replies. */
    public val locale: Locale

    /** The platform's native sender object. */
    public val handle: Any

    public fun hasPermission(permission: String): Boolean

    /** Sends plain text. */
    public fun sendMessage(message: String)

    /**
     * Sends a translated message. Adapters override this to render rich text, for example MiniMessage on Paper;
     * the default formats plain text.
     */
    public fun sendTranslated(translations: Translations, key: String, placeholders: Map<String, Any?>) {
        sendMessage(translations.format(locale, key, placeholders))
    }

    /**
     * Returns this source as [type] or `null`. The default checks [handle] and the source itself; adapters extend
     * this, for example to return the executing player of a Paper command source stack.
     */
    public fun <T : Any> unwrap(type: Class<T>): T? = when {
        type.isInstance(handle) -> type.cast(handle)
        type.isInstance(this) -> type.cast(this)
        else -> null
    }
}
