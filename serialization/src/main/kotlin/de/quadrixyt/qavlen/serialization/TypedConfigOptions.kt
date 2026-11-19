package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigMigration
import de.quadrixyt.qavlen.config.ConfigViolation

/** What happens to keys in a file that no property reads, for example after a property was removed. */
public enum class UnknownKeys {
    /** Keep them in the file. */
    KEEP,

    /** Keep them and log a warning naming them. */
    WARN,

    /** Remove them when the file is written. */
    REMOVE,
}

/** Checks a decoded configuration value and returns every problem found. */
public fun interface TypedValidator<T> {
    public fun validate(value: T): List<ConfigViolation>
}

/**
 * Options of a [TypedConfig]. The defaults suit most configurations: the format follows the file extension,
 * missing values are written to the file, unknown keys are kept and validation annotations are checked.
 */
public class TypedConfigOptions<T : Any> private constructor(builder: Builder<T>) {
    public val format: ConfigFileFormat? = builder.format
    public val prefix: String? = builder.prefix
    public val unknownKeys: UnknownKeys = builder.unknownKeys
    public val writeDefaults: Boolean = builder.writeDefaults
    public val backups: Boolean = builder.backups
    public val version: Int? = builder.version
    public val versionKey: String = builder.versionKey
    public val migrations: List<ConfigMigration> = builder.migrations.sortedBy { it.fromVersion }
    public val validators: List<TypedValidator<in T>> = builder.validators.toList()
    public val codec: JsonCodec = builder.codec
    internal val sources: List<OverrideSource> = builder.sources.toList()

    public class Builder<T : Any> internal constructor() {
        internal var format: ConfigFileFormat? = null
        internal var prefix: String? = null
        internal var unknownKeys = UnknownKeys.KEEP
        internal var writeDefaults = true
        internal var backups = true
        internal var version: Int? = null
        internal var versionKey = "config-version"
        internal val migrations = mutableListOf<ConfigMigration>()
        internal val validators = mutableListOf<TypedValidator<in T>>()
        internal val sources = mutableListOf<OverrideSource>()
        internal var codec: JsonCodec = ConfigBinding.codec

        /** Uses [format] instead of guessing it from the file extension. */
        public fun format(format: ConfigFileFormat): Builder<T> = apply { this.format = format }

        /** Binds only the section at [path], for example `database`; the rest of the file is kept as it is. */
        public fun prefix(path: String): Builder<T> = apply {
            require(path.isNotBlank() && path.split('.').none(String::isEmpty)) { "Invalid prefix '$path'" }
            prefix = path
        }

        public fun unknownKeys(policy: UnknownKeys): Builder<T> = apply { unknownKeys = policy }

        /** Writes missing values with their defaults to the file. Enabled by default. */
        public fun writeDefaults(enabled: Boolean): Builder<T> = apply { writeDefaults = enabled }

        /** Copies the file before a migration rewrites it. Enabled by default. */
        public fun backups(enabled: Boolean): Builder<T> = apply { backups = enabled }

        /** Enables versioned migrations; files without the version key count as version 1. */
        @JvmOverloads
        public fun version(current: Int, key: String = "config-version"): Builder<T> = apply {
            require(current >= 1) { "Configuration versions start at 1" }
            version = current
            versionKey = key
        }

        public fun migration(fromVersion: Int, migrator: ConfigMigration.Migrator): Builder<T> =
            apply { migrations += ConfigMigration.of(fromVersion, migrator) }

        /** Adds a check that runs after the annotations, on every load, reload and update. */
        public fun validator(validator: TypedValidator<in T>): Builder<T> = apply { validators += validator }

        /**
         * Lets environment variables override values, for example `MYPLUGIN_DATABASE_PORT` for `database.port` with
         * prefix `MYPLUGIN`. Overrides are never written to the file.
         */
        @JvmOverloads
        public fun environment(prefix: String, variables: Map<String, String> = System.getenv()): Builder<T> = apply {
            sources += OverrideSource.Environment(prefix.trimEnd('_').uppercase(), variables.toMap())
        }

        /** Lets system properties override values, for example `myplugin.database.port`. Never written to the file. */
        @JvmOverloads
        public fun systemProperties(prefix: String, properties: Map<String, String> = systemProperties()): Builder<T> = apply {
            sources += OverrideSource.Properties(prefix.trimEnd('.'), properties.toMap())
        }

        /** Overrides values by path, for example from command-line arguments. Never written to the file. */
        public fun overrides(values: Map<String, Any?>): Builder<T> = apply { sources += OverrideSource.Values(values.toMap()) }

        /** Uses a codec with custom serializers; it should keep kebab-case names to match existing files. */
        public fun codec(codec: JsonCodec): Builder<T> = apply { this.codec = codec }

        public fun build(): TypedConfigOptions<T> {
            val current = version
            if (current == null) {
                require(migrations.isEmpty()) { "Migrations require version(...)" }
            } else {
                for (from in 1 until current) {
                    require(migrations.count { it.fromVersion == from } == 1) { "Expected one migration from version $from" }
                }
            }
            return TypedConfigOptions(this)
        }

        private fun systemProperties(): Map<String, String> =
            System.getProperties().stringPropertyNames().associateWith { System.getProperty(it) }
    }

    public companion object {
        @JvmStatic
        public fun <T : Any> builder(): Builder<T> = Builder()

        @JvmStatic
        public fun <T : Any> defaults(): TypedConfigOptions<T> = Builder<T>().build()
    }
}

/** Values that take precedence over the file, in the order sources were added; later sources win. */
internal sealed interface OverrideSource {
    fun values(paths: List<String>): Map<String, Any?>

    class Environment(private val prefix: String, private val variables: Map<String, String>) : OverrideSource {
        override fun values(paths: List<String>): Map<String, Any?> = paths.mapNotNull { path ->
            val name = prefix + "_" + path.uppercase().replace('.', '_').replace('-', '_')
            variables[name]?.let { path to it }
        }.toMap()
    }

    class Properties(private val prefix: String, private val properties: Map<String, String>) : OverrideSource {
        override fun values(paths: List<String>): Map<String, Any?> =
            paths.mapNotNull { path -> properties["$prefix.$path"]?.let { path to it } }.toMap()
    }

    class Values(private val values: Map<String, Any?>) : OverrideSource {
        override fun values(paths: List<String>): Map<String, Any?> = values
    }
}
