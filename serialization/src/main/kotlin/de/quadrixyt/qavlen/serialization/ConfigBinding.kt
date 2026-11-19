package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.ConfigSection
import de.quadrixyt.qavlen.config.ConfigValidationException
import de.quadrixyt.qavlen.config.ConfigViolation
import de.quadrixyt.qavlen.core.Durations
import tools.jackson.core.JacksonException
import tools.jackson.databind.PropertyNamingStrategies
import java.time.Duration

/**
 * Reads configuration sections into typed classes and writes them back.
 *
 * ```kotlin
 * data class DatabaseSettings(
 *     val host: String = "localhost",
 *     val port: Int = 3306,
 *     val poolSize: Int = 4,
 *     val timeout: Duration = Duration.ofSeconds(10),
 * )
 *
 * val database = config.bind<DatabaseSettings>("database")
 * ```
 *
 * ```yaml
 * database:
 *   host: db.internal
 *   pool-size: 8
 *   timeout: 30s
 * ```
 *
 * Property names are written in kebab case (`poolSize` becomes `pool-size`), durations accept the compact form
 * (`30s`, `1h30m`) as well as ISO-8601, enum constants ignore case, and Kotlin default values fill missing keys. A missing value without a
 * default, or a value of the wrong type, fails with a [ConfigException] that names the full path. Unknown keys are
 * ignored, so a newer configuration file still loads with older code.
 *
 * Validation annotations such as [Range] and [NotBlank] are checked, as for [Configs].
 *
 * Kotlin classes, records and Java beans are supported, as with [JsonCodec].
 */
public object ConfigBinding {
    /** The codec used for configuration binding: kebab-case names and compact durations. */
    @JvmStatic
    public val codec: JsonCodec by lazy {
        JsonCodec.builder()
            .propertyNaming(PropertyNamingStrategies.KEBAB_CASE)
            .caseInsensitiveEnums(true)
            .stringType(Duration::class.java, Durations::format, Durations::parse)
            .build()
    }

    /** Reads the whole [section] as [type]. */
    @JvmStatic
    @JvmOverloads
    public fun <T> bind(section: ConfigSection, type: Class<T>, codec: JsonCodec = ConfigBinding.codec): T = try {
        codec.convert(section.toMap(), type).also { value -> validate(value, section.path, codec) }
    } catch (exception: SerializationException) {
        val path = listOf(section.path, exception.path.orEmpty()).filter(String::isNotEmpty).joinToString(".")
        val reason = (exception.cause as? JacksonException)?.originalMessage ?: exception.message
        throw ConfigException("Invalid configuration${if (path.isEmpty()) "" else " at '$path'"}: $reason", exception)
    }

    /** Reads the section at [path] as [type]. A missing section is read as empty, so defaults of [type] apply. */
    @JvmStatic
    @JvmOverloads
    public fun <T> bind(section: ConfigSection, path: String, type: Class<T>, codec: JsonCodec = ConfigBinding.codec): T {
        val nested = section.getSection(path) ?: return bind(EmptySection(join(section.path, path)), type, codec)
        return bind(nested, type, codec)
    }

    /** Checks the validation annotations of [value]'s class, such as [Range], and throws for every violation at once. */
    private fun validate(value: Any?, path: String, codec: JsonCodec) {
        val model = value?.let { ConfigModel.of(it.javaClass, codec) } ?: return
        val violations = mutableListOf<ConfigViolation>()
        model.validate(toMap(value, codec), path, violations)
        if (violations.isNotEmpty()) throw ConfigValidationException(violations)
    }

    /** Converts [value] into the map form used by configurations, for example to pass it to `ConfigOptions.defaults`. */
    @JvmStatic
    @JvmOverloads
    public fun toMap(value: Any, codec: JsonCodec = ConfigBinding.codec): Map<String, Any?> {
        val map = codec.convert(value, Map::class.java)
        return map.entries.associate { (key, entry) -> key.toString() to entry }
    }

    /** Writes [value] as a section at [path]. Remember to save the configuration afterwards. */
    @JvmStatic
    @JvmOverloads
    public fun write(section: ConfigSection, path: String, value: Any, codec: JsonCodec = ConfigBinding.codec) {
        section.set(path, toMap(value, codec))
    }

    private fun join(prefix: String, path: String) = if (prefix.isEmpty()) path else "$prefix.$path"

    private class EmptySection(override val path: String) : ConfigSection {
        override fun contains(path: String): Boolean = false

        override fun get(path: String): Any? = null

        override fun set(path: String, value: Any?): Unit = throw UnsupportedOperationException()

        override fun remove(path: String): Boolean = false

        override fun keys(deep: Boolean): Set<String> = emptySet()

        override fun toMap(): Map<String, Any?> = emptyMap()

        override fun getSection(path: String): ConfigSection? = null
    }
}

/** Kotlin shorthand for [ConfigBinding.bind]: `val settings = config.bind<Settings>()`. */
public inline fun <reified T> ConfigSection.bind(): T = ConfigBinding.bind(this, T::class.java)

/** Kotlin shorthand for [ConfigBinding.bind] with a path: `config.bind<DatabaseSettings>("database")`. */
public inline fun <reified T> ConfigSection.bind(path: String): T = ConfigBinding.bind(this, path, T::class.java)
