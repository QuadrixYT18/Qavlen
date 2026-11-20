package de.quadrixyt.qavlen.language

/**
 * A named value inserted into a [MessageTemplate]. Numbers, dates, times and durations are formatted for the
 * target locale; other values use `toString()`. Platform adapters may accept richer values such as Adventure
 * components.
 */
public class Placeholder private constructor(public val name: String, public val value: Any?) {
    override fun toString(): String = "$name=$value"

    override fun equals(other: Any?): Boolean = other is Placeholder && other.name == name && other.value == value

    override fun hashCode(): Int = 31 * name.hashCode() + value.hashCode()

    public companion object {
        @JvmStatic
        public fun of(name: String, value: Any?): Placeholder {
            require(name.isNotBlank()) { "Placeholder names must not be blank" }
            return Placeholder(name, value)
        }
    }
}

/** Kotlin shorthand for [Placeholder.of]: `translations.format(locale, "welcome", placeholder("player", name))`. */
public fun placeholder(name: String, value: Any?): Placeholder = Placeholder.of(name, value)
