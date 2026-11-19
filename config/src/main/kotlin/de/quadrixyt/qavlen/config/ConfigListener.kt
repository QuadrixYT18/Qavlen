package de.quadrixyt.qavlen.config

/**
 * Receives changes of a [ConfigFile]. Listeners run on the thread that made the change, after the change is
 * complete and outside the configuration's lock, so they may read or modify the configuration.
 */
public fun interface ConfigListener {
    public fun onChange(change: ConfigChange)
}

/**
 * A completed change.
 *
 * @property paths changed paths: the path that was set or removed, or every leaf path whose value differs after a reload
 * @property reload `true` when the change was caused by [ConfigFile.reload]
 */
public class ConfigChange internal constructor(
    public val config: ConfigFile,
    public val paths: Set<String>,
    public val reload: Boolean,
) {
    /** `true` when [path] or anything below it changed, for example `affects("database")` for `database.port`. */
    public fun affects(path: String): Boolean = paths.any { it == path || it.startsWith("$path.") || path.startsWith("$it.") }

    override fun toString(): String = "ConfigChange(paths=$paths, reload=$reload)"
}
