package de.quadrixyt.qavlen.config

/** A configuration could not be read, written, migrated or validated. */
public open class ConfigException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)

/** A stored value exists but cannot be read as the requested type. */
public class ConfigValueException(
    public val valuePath: String,
    expected: String,
    actual: Any?,
) : ConfigException("'$valuePath' must be $expected but was ${describe(actual)}")

/** One or more values failed validation. Nothing is replaced or written when this is thrown. */
public class ConfigValidationException(public val violations: List<ConfigViolation>) :
    ConfigException(violations.joinToString(prefix = "Configuration validation failed:\n  ", separator = "\n  "))

/** A single validation problem at [path]. */
public data class ConfigViolation(val path: String, val message: String) {
    override fun toString(): String = if (path.isEmpty()) message else "$path: $message"
}

private fun describe(value: Any?): String = when (value) {
    null -> "null"
    is Map<*, *> -> "a section"
    is List<*> -> "a list"
    is String -> "the string \"$value\""
    else -> "${value.javaClass.simpleName.lowercase()} $value"
}

/** The file was changed by someone else since it was loaded, so saving would overwrite those changes. */
public open class ConfigConflictException(message: String) : ConfigException(message)
