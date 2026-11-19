package de.quadrixyt.qavlen.config

/** Value kinds a [ConfigSchema] can require. */
public enum class ConfigType(internal val description: String) {
    STRING("a string"),
    INTEGER("an integer"),
    DECIMAL("a number"),
    BOOLEAN("true or false"),
    LIST("a list"),
    SECTION("a section"),
    ;

    internal fun matches(value: Any?): Boolean = when (this) {
        STRING -> value is String
        INTEGER -> value is Int || value is Long || value is java.math.BigInteger
        DECIMAL -> value is Number
        BOOLEAN -> value is Boolean
        LIST -> value is List<*>
        SECTION -> value is Map<*, *>
    }
}

/**
 * Declares the expected structure of a configuration and reports every deviation at once: missing required
 * values, wrong types, values outside a range or set, and, with [Builder.rejectUnknownKeys], keys nobody reads,
 * which are usually typos.
 *
 * ```kotlin
 * val schema = ConfigSchema.builder()
 *     .required("database.host", ConfigType.STRING)
 *     .optional("database.port", ConfigType.INTEGER).range("database.port", 1, 65535)
 *     .optional("mode", ConfigType.STRING).oneOf("mode", "fast", "safe")
 *     .rejectUnknownKeys()
 *     .build()
 *
 * YamlConfig.load(path, ConfigOptions { schema(schema) })
 * ```
 *
 * A schema is a [ConfigValidator], so a violation prevents loading, reloading and saving.
 */
public class ConfigSchema private constructor(
    private val fields: Map<String, Field>,
    private val strict: Boolean,
    private val known: Set<String>,
) : ConfigValidator {
    private class Field(
        val type: ConfigType,
        val required: Boolean,
        val range: ClosedFloatingPointRange<Double>?,
        val choices: Set<String>?,
    )

    override fun validate(config: ConfigSection): List<ConfigViolation> {
        val violations = fields.mapNotNull { (path, field) -> check(config, path, field)?.let { ConfigViolation(path, it) } }
        if (!strict) return violations
        return violations + config.keys(true).filterNot(::isDeclared).map { ConfigViolation(it, "is not a known setting") }
    }

    private fun check(config: ConfigSection, path: String, field: Field): String? {
        if (!config.contains(path)) return if (field.required) "is required" else null
        val value = config.get(path) ?: return if (field.required) "must not be empty" else null
        if (!field.type.matches(value)) return "must be ${field.type.description}"
        val range = field.range
        if (range != null && (value as Number).toDouble() !in range) {
            return "must be between ${format(range.start)} and ${format(range.endInclusive)}"
        }
        val choices = field.choices
        if (choices != null && value.toString() !in choices) return "must be one of ${choices.joinToString(", ")}"
        return null
    }

    /** A copy that also accepts [path] as known, for example the version key added by migrations. */
    internal fun allowing(path: String): ConfigSchema = ConfigSchema(fields, strict, known + path)

    /** Declared paths, paths below sections and lists, and parents of declared paths are known. */
    private fun isDeclared(path: String): Boolean = path in known ||
        fields.any { (declared, field) ->
            path == declared ||
                declared.startsWith("$path.") ||
                (field.type == ConfigType.SECTION || field.type == ConfigType.LIST) &&
                path.startsWith("$declared.")
        }

    private fun format(value: Double): String = if (value % 1.0 == 0.0 &&
        kotlin.math.abs(value) < 1e15
    ) {
        value.toLong().toString()
    } else {
        value.toString()
    }

    public class Builder internal constructor() {
        private val fields = linkedMapOf<String, Field>()
        private var strict = false

        public fun required(path: String, type: ConfigType): Builder = field(path, type, required = true)

        public fun optional(path: String, type: ConfigType): Builder = field(path, type, required = false)

        /** Restricts a declared number to [min]..[max]. */
        public fun range(path: String, min: Double, max: Double): Builder = update(path) { field ->
            require(field.type == ConfigType.INTEGER || field.type == ConfigType.DECIMAL) { "'$path' is not numeric" }
            require(min <= max) { "min must not exceed max" }
            Field(field.type, field.required, min..max, field.choices)
        }

        public fun range(path: String, min: Long, max: Long): Builder = range(path, min.toDouble(), max.toDouble())

        /** Restricts a declared value to the given choices, compared as text. */
        public fun oneOf(path: String, vararg choices: String): Builder = update(path) { field ->
            require(choices.isNotEmpty()) { "At least one choice is required" }
            Field(field.type, field.required, field.range, choices.toSet())
        }

        /** Reports keys that are not declared. Keys inside declared sections and lists are always accepted. */
        public fun rejectUnknownKeys(): Builder = apply { strict = true }

        public fun build(): ConfigSchema = ConfigSchema(fields.toMap(), strict, emptySet())

        private fun field(path: String, type: ConfigType, required: Boolean): Builder = apply {
            segments(path)
            require(path !in fields) { "'$path' is declared twice" }
            fields[path] = Field(type, required, null, null)
        }

        private fun update(path: String, change: (Field) -> Field): Builder = apply {
            val field = requireNotNull(fields[path]) { "Declare '$path' with required(...) or optional(...) first" }
            fields[path] = change(field)
        }
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
