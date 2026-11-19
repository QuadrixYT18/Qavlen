package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import java.io.InputStream
import java.nio.file.Path

/**
 * Entry point for typed configurations.
 *
 * ```kotlin
 * @Configuration
 * data class PluginConfig(
 *     @Comment("Maximum player count")
 *     @Range(min = 1.0, max = 100.0)
 *     val maxPlayers: Int = 20,
 *     val database: DatabaseConfig = DatabaseConfig(),
 * )
 *
 * val config = Configs.open<PluginConfig>(Path.of("config.yml"))
 * config.update { copy(maxPlayers = 50) }
 * config.save()
 * ```
 *
 * A configuration without a file is simply an instance: `PluginConfig()`.
 */
public object Configs {
    /** Opens [path], creating it from the defaults of [type] when it does not exist. The format follows the extension. */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> open(path: Path, type: Class<T>, options: TypedConfigOptions<T> = TypedConfigOptions.defaults()): TypedConfig<T> {
        val format = options.format ?: ConfigFileFormat.fromFileName(path.fileName.toString())
        return TypedConfig(type, path, format, options, null)
    }

    /** A configuration in memory, read from [text]. It can be encoded but not saved. */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> parse(
        text: String,
        type: Class<T>,
        format: ConfigFileFormat,
        options: TypedConfigOptions<T> = TypedConfigOptions.defaults(),
    ): TypedConfig<T> = TypedConfig(type, null, format, options, text)

    /** Like [parse], reading UTF-8 text from [input], which is closed. */
    @JvmStatic
    @JvmOverloads
    public fun <T : Any> read(
        input: InputStream,
        type: Class<T>,
        format: ConfigFileFormat,
        options: TypedConfigOptions<T> = TypedConfigOptions.defaults(),
    ): TypedConfig<T> = parse(input.use { it.readBytes().toString(Charsets.UTF_8) }, type, format, options)

    /** Encodes [value] in [format], with comments for YAML. */
    @JvmStatic
    @JvmOverloads
    public fun encode(value: Any, format: ConfigFileFormat = ConfigFileFormat.YAML, codec: JsonCodec = ConfigBinding.codec): String {
        val tree = ConfigBinding.toMap(value, codec)
        val text = format.write(tree, ConfigModel.of(value.javaClass, codec))
        if (format == ConfigFileFormat.TOML &&
            ConfigFileFormat.nullPaths(tree).isNotEmpty() &&
            codec.convert(format.read(text, "TOML"), value.javaClass) != value
        ) {
            throw ConfigException(
                "TOML cannot store null at ${ConfigFileFormat.nullPaths(tree).joinToString {
                    "'$it'"
                }} without changing the value",
            )
        }
        return text
    }

    /** Kotlin form of [open]: `Configs.open<PluginConfig>(path) { environment("MYPLUGIN") }`. */
    @JvmSynthetic
    public inline fun <reified T : Any> open(path: Path, configure: TypedConfigOptions.Builder<T>.() -> Unit = {}): TypedConfig<T> =
        open(path, T::class.java, TypedConfigOptions.builder<T>().apply(configure).build())

    /** Kotlin form of [parse]: `Configs.parse<PluginConfig>(text, ConfigFileFormat.YAML)`. */
    @JvmSynthetic
    public inline fun <reified T : Any> parse(
        text: String,
        format: ConfigFileFormat,
        configure: TypedConfigOptions.Builder<T>.() -> Unit = {},
    ): TypedConfig<T> = parse(text, T::class.java, format, TypedConfigOptions.builder<T>().apply(configure).build())
}
