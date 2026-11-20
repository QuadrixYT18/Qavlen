package de.quadrixyt.qavlen.config

import net.kyori.adventure.text.Component

/**
 * A view of nested configuration values addressed by dot-separated paths such as `database.port`.
 *
 * Typed getters return `null` when a value is absent and throw [ConfigValueException] when a value exists but
 * has an incompatible type, so typos in a file are reported instead of silently replaced by defaults.
 */
public interface ConfigSection {
    /** Absolute path of this section, or an empty string for the document root. */
    public val path: String

    public fun contains(path: String): Boolean

    /** Returns a detached copy of the raw value: a `Map`, `List`, `String`, `Number`, `Boolean` or `null`. */
    public fun get(path: String): Any?

    /** Sets a value, creating intermediate sections. Maps and lists are copied. */
    public fun set(path: String, value: Any?)

    /** Removes a value. Returns `false` when nothing was stored at [path]. */
    public fun remove(path: String): Boolean

    /** Direct child keys, or every leaf path relative to this section when [deep] is `true`. */
    public fun keys(deep: Boolean): Set<String>

    /** A detached deep copy of this section. */
    public fun toMap(): Map<String, Any?>

    /** Returns a live view of a nested section, or `null` when [path] is absent or not a section. */
    public fun getSection(path: String): ConfigSection?

    public fun keys(): Set<String> = keys(false)

    public fun getString(path: String): String? = when (val value = get(path)) {
        null -> null
        is String -> value
        is Number, is Boolean -> value.toString()
        else -> throw ConfigValueException(qualify(path), "a string", value)
    }

    public fun getString(path: String, default: String): String = getString(path) ?: default

    public fun getInt(path: String): Int? = getLong(path)?.let { value ->
        if (value !in Int.MIN_VALUE..Int.MAX_VALUE) {
            throw ConfigValueException(qualify(path), "an integer in the Int range", value)
        }
        value.toInt()
    }

    public fun getInt(path: String, default: Int): Int = getInt(path) ?: default

    public fun getLong(path: String): Long? = when (val value = get(path)) {
        null -> null
        is Int, is Long, is Short, is Byte -> (value as Number).toLong()
        is Number -> value.toDouble().let { number ->
            if (number % 1.0 != 0.0 || number !in Long.MIN_VALUE.toDouble()..Long.MAX_VALUE.toDouble()) {
                throw ConfigValueException(qualify(path), "an integer", value)
            }
            number.toLong()
        }
        is String -> value.trim().toLongOrNull() ?: throw ConfigValueException(qualify(path), "an integer", value)
        else -> throw ConfigValueException(qualify(path), "an integer", value)
    }

    public fun getLong(path: String, default: Long): Long = getLong(path) ?: default

    public fun getDouble(path: String): Double? = when (val value = get(path)) {
        null -> null
        is Number -> value.toDouble()
        is String -> value.trim().toDoubleOrNull() ?: throw ConfigValueException(qualify(path), "a number", value)
        else -> throw ConfigValueException(qualify(path), "a number", value)
    }

    public fun getDouble(path: String, default: Double): Double = getDouble(path) ?: default

    public fun getBoolean(path: String): Boolean? = when (val value = get(path)) {
        null -> null
        is Boolean -> value
        "true" -> true
        "false" -> false
        else -> throw ConfigValueException(qualify(path), "a boolean", value)
    }

    public fun getBoolean(path: String, default: Boolean): Boolean = getBoolean(path) ?: default

    public fun getList(path: String): List<Any?>? = when (val value = get(path)) {
        null -> null
        is List<*> -> value
        else -> throw ConfigValueException(qualify(path), "a list", value)
    }

    public fun getStringList(path: String): List<String> = getList(path).orEmpty().map { element ->
        when (element) {
            is String -> element
            is Number, is Boolean -> element.toString()
            else -> throw ConfigValueException(qualify(path), "a list of strings", element)
        }
    }

    public fun getIntList(path: String): List<Int> = getList(path).orEmpty().map { element ->
        (element as? Number)?.takeIf { it is Int || it is Long || it is Short || it is Byte }?.toLong()
            ?.takeIf { it in Int.MIN_VALUE..Int.MAX_VALUE }?.toInt()
            ?: throw ConfigValueException(qualify(path), "a list of integers", element)
    }

    /** Returns a detached copy of a nested section as a map. */
    public fun getMap(path: String): Map<String, Any?>? = when (val value = get(path)) {
        null -> null
        is Map<*, *> -> value.entries.associate { (key, entry) -> key.toString() to entry }
        else -> throw ConfigValueException(qualify(path), "a section", value)
    }

    /**
     * Reads [path] as a MiniMessage text and renders it with [MessageComponents], or returns `null` when [path] is
     * absent. Placeholder values are inserted as literal text; `<prefix>` renders the `prefix` value of this section.
     * Needs Adventure and MiniMessage at runtime, which Paper and Velocity provide.
     *
     * @throws ConfigException when the value is not text or its placeholders are malformed
     */
    public fun getComponent(path: String, placeholders: Map<String, Any?>): Component? {
        val text = getString(path) ?: return null
        return message(path) { MessageComponents.render(text, placeholders, prefixFor(text)) }
    }

    public fun getComponent(path: String): Component? = getComponent(path, emptyMap())

    /** Kotlin form: `config.getComponent("welcome", "player" to name)`. */
    public fun getComponent(path: String, vararg placeholders: Pair<String, Any?>): Component? =
        getComponent(path, placeholders.toMap())

    /** Like [getComponent], but a missing value is an error instead of `null`. */
    public fun requireComponent(path: String, placeholders: Map<String, Any?>): Component =
        getComponent(path, placeholders) ?: throw ConfigException("Missing message '${qualify(path)}'")

    public fun requireComponent(path: String): Component = requireComponent(path, emptyMap())

    public fun requireComponent(path: String, vararg placeholders: Pair<String, Any?>): Component =
        requireComponent(path, placeholders.toMap())

    /**
     * Renders a list of texts, or a multi-line text, as one component per line, for item lore or scoreboards.
     * Returns an empty list when [path] is absent.
     */
    public fun getComponentList(path: String, placeholders: Map<String, Any?>): List<Component> {
        val lines = if (get(path) is List<*>) getStringList(path) else getString(path)?.split('\n').orEmpty()
        return lines.map { line -> message(path) { MessageComponents.render(line, placeholders, prefixFor(line)) } }
    }

    public fun getComponentList(path: String): List<Component> = getComponentList(path, emptyMap())

    public fun getComponentList(path: String, vararg placeholders: Pair<String, Any?>): List<Component> =
        getComponentList(path, placeholders.toMap())

    private fun prefixFor(text: String): Component? =
        if ("<prefix>" in text) getString(PREFIX_KEY)?.let { MessageComponents.render(it) } ?: Component.empty() else null

    private fun message(path: String, render: () -> Component): Component = try {
        render()
    } catch (failure: IllegalArgumentException) {
        throw ConfigException("Invalid message at '${qualify(path)}': ${failure.message}", failure)
    }

    private fun qualify(relative: String): String = if (path.isEmpty()) relative else "$path.$relative"

    public companion object {
        private const val PREFIX_KEY = "prefix"

        /**
         * A section over a copy of [values] without a file, for example to run migrations or validators on a tree
         * that comes from another source. Changes affect only the copy; read it back with [toMap].
         */
        @JvmStatic
        public fun of(values: Map<String, Any?>): ConfigSection = detachedSection(deepCopy(values))
    }
}
