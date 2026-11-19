package de.quadrixyt.qavlen.config

/**
 * How a [ConfigFile] is loaded. Instances are immutable; use [builder] or [ConfigOptions.DEFAULT].
 *
 * @property defaults values added to the document when missing; existing values are never replaced
 * @property version current schema version, or `null` to disable migrations
 * @property versionKey path that stores the schema version inside the document
 * @property backups copy the previous file before a migration rewrites it
 * @property writeDefaults write the file when defaults or migrations changed the loaded document
 */
public class ConfigOptions private constructor(
    public val defaults: Map<String, Any?>,
    public val validators: List<ConfigValidator>,
    public val migrations: List<ConfigMigration>,
    public val version: Int?,
    public val versionKey: String,
    public val backups: Boolean,
    public val writeDefaults: Boolean,
) {
    public fun toBuilder(): Builder = Builder().also { builder ->
        builder.defaults.putAll(defaults)
        builder.validators += validators
        builder.migrations += migrations
        builder.version = version
        builder.versionKey = versionKey
        builder.backups = backups
        builder.writeDefaults = writeDefaults
    }

    public class Builder internal constructor() {
        internal val defaults = linkedMapOf<String, Any?>()
        internal val validators = mutableListOf<ConfigValidator>()
        internal val schemas = mutableListOf<ConfigSchema>()
        internal val migrations = mutableListOf<ConfigMigration>()
        internal var version: Int? = null
        internal var versionKey = "config-version"
        internal var backups = true
        internal var writeDefaults = true

        /** Adds default values. Later calls are merged into earlier ones without replacing existing keys. */
        public fun defaults(values: Map<String, Any?>): Builder = apply { mergeDefaults(defaults, values) }

        /** Uses another document, typically read from a bundled resource, as defaults. */
        public fun defaults(section: ConfigSection): Builder = defaults(section.toMap())

        public fun validator(validator: ConfigValidator): Builder = apply { validators += validator }

        /** Validates against [schema]. The version key of migrations is accepted even when the schema rejects unknown keys. */
        public fun schema(schema: ConfigSchema): Builder = apply { schemas += schema }

        /** Enables versioned migrations. Documents without a version key are treated as version 1. */
        @JvmOverloads
        public fun version(current: Int, key: String = "config-version"): Builder = apply {
            require(current >= 1) { "Configuration versions start at 1" }
            segments(key)
            version = current
            versionKey = key
        }

        public fun migration(migration: ConfigMigration): Builder = apply { migrations += migration }

        public fun migration(fromVersion: Int, migrator: ConfigMigration.Migrator): Builder =
            migration(ConfigMigration.of(fromVersion, migrator))

        public fun backups(enabled: Boolean): Builder = apply { backups = enabled }

        public fun writeDefaults(enabled: Boolean): Builder = apply { writeDefaults = enabled }

        public fun build(): ConfigOptions {
            val current = version
            if (current == null) {
                require(migrations.isEmpty()) { "Migrations require version(...)" }
            } else {
                val steps = migrations.groupBy { it.fromVersion }
                steps.forEach { (from, list) -> require(list.size == 1) { "More than one migration from version $from" } }
                for (from in 1 until current) require(from in steps) { "Missing migration from version $from to ${from + 1}" }
                require(steps.keys.all { it in 1 until current }) { "Migrations must start between 1 and ${current - 1}" }
            }
            val schemaValidators = schemas.map { schema -> if (current == null) schema else schema.allowing(versionKey) }
            return ConfigOptions(
                deepCopy(defaults),
                validators + schemaValidators,
                migrations.sortedBy { it.fromVersion },
                current,
                versionKey,
                backups,
                writeDefaults,
            )
        }
    }

    public companion object {
        @JvmField
        public val DEFAULT: ConfigOptions = Builder().build()

        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}

/** Kotlin builder: `ConfigOptions { defaults(mapOf("port" to 25565)) }`. */
public inline fun ConfigOptions(configure: ConfigOptions.Builder.() -> Unit): ConfigOptions =
    ConfigOptions.builder().apply(configure).build()
