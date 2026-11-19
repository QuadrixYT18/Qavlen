package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.language.Placeholder

/**
 * Ends command execution and replies with a translated message. Throw it from executors and argument types for
 * expected failures; unexpected exceptions are logged and answered with a generic error.
 *
 * @property key translation key, for example `qavlen:command.no-permission` or a key of your own translations
 */
public open class CommandException @JvmOverloads constructor(
    public val key: String,
    public val placeholders: Map<String, Any?> = emptyMap(),
    cause: Throwable? = null,
) : RuntimeException(key, cause, true, false) {
    public constructor(key: String, vararg placeholders: Placeholder) : this(key, placeholders.associate { it.name to it.value })

    public companion object {
        public const val NO_PERMISSION: String = "qavlen:command.no-permission"
        public const val UNKNOWN_COMMAND: String = "qavlen:command.unknown"
        public const val USAGE: String = "qavlen:command.usage"
        public const val TOO_MANY_ARGUMENTS: String = "qavlen:command.too-many-arguments"
        public const val INVALID_ARGUMENT: String = "qavlen:command.invalid-argument"
        public const val INVALID_NUMBER: String = "qavlen:command.invalid-number"
        public const val NUMBER_RANGE: String = "qavlen:command.number-range"
        public const val STRING_LENGTH: String = "qavlen:command.string-length"
        public const val HELP_HEADER: String = "qavlen:command.help.header"
        public const val HELP_ENTRY: String = "qavlen:command.help.entry"
        public const val HELP_ENTRY_PLAIN: String = "qavlen:command.help.entry-plain"
        public const val INVALID_BOOLEAN: String = "qavlen:command.invalid-boolean"
        public const val INVALID_CHOICE: String = "qavlen:command.invalid-choice"
        public const val INVALID_UUID: String = "qavlen:command.invalid-uuid"
        public const val INVALID_DURATION: String = "qavlen:command.invalid-duration"
        public const val SENDER_TYPE: String = "qavlen:command.sender-type"
        public const val COOLDOWN: String = "qavlen:command.cooldown"
        public const val FAILED: String = "qavlen:command.failed"
    }
}
