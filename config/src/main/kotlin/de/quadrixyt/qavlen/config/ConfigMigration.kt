package de.quadrixyt.qavlen.config

/**
 * Upgrades a document from [fromVersion] to `fromVersion + 1`.
 *
 * Migrations run against a detached copy; the file is only replaced after every migration, default merge and
 * validator succeeded. When [ConfigOptions.backups] is enabled the previous file is copied first.
 */
public interface ConfigMigration {
    public val fromVersion: Int

    public fun migrate(config: ConfigSection)

    public companion object {
        /** Creates a migration from a lambda or Java method reference. */
        @JvmStatic
        public fun of(fromVersion: Int, migration: Migrator): ConfigMigration = object : ConfigMigration {
            override val fromVersion: Int = fromVersion

            override fun migrate(config: ConfigSection) = migration.migrate(config)
        }
    }

    /** Functional form of [ConfigMigration.migrate]. */
    public fun interface Migrator {
        public fun migrate(config: ConfigSection)
    }
}

/** Checks a candidate document. Violations prevent loading, reloading and saving. */
public fun interface ConfigValidator {
    public fun validate(config: ConfigSection): List<ConfigViolation>

    public companion object {
        /** Requires every given path to be present. */
        @JvmStatic
        public fun required(vararg paths: String): ConfigValidator = ConfigValidator { config ->
            paths.filterNot(config::contains).map { ConfigViolation(it, "is required") }
        }

        /** Requires an integer value at [path] to be within [min]..[max] when present. */
        @JvmStatic
        public fun range(path: String, min: Long, max: Long): ConfigValidator = ConfigValidator { config ->
            val value = config.getLong(path)
            if (value == null || value in min..max) emptyList() else listOf(ConfigViolation(path, "must be between $min and $max"))
        }
    }
}
