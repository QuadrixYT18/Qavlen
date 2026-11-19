package de.quadrixyt.qavlen.serialization

import tools.jackson.core.JacksonException
import tools.jackson.core.JsonGenerator
import tools.jackson.core.JsonParser
import tools.jackson.core.type.TypeReference
import tools.jackson.databind.DatabindException
import tools.jackson.databind.DeserializationContext
import tools.jackson.databind.DeserializationFeature
import tools.jackson.databind.JacksonModule
import tools.jackson.databind.JavaType
import tools.jackson.databind.MapperFeature
import tools.jackson.databind.PropertyNamingStrategy
import tools.jackson.databind.SerializationContext
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.ValueDeserializer
import tools.jackson.databind.ValueSerializer
import tools.jackson.databind.cfg.DateTimeFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.databind.module.SimpleModule
import tools.jackson.module.kotlin.KotlinModule
import java.io.InputStream
import java.util.function.Function

/** JSON could not be written or read. [path] points at the offending value, for example `players[2].uuid`. */
public class SerializationException(message: String, public val path: String?, cause: Throwable?) : RuntimeException(message, cause)

/**
 * Reads and writes JSON with Jackson.
 *
 * Kotlin classes, records and Java beans work out of the box, including constructor defaults and null-safety for
 * Kotlin. `java.time` values such as `Instant` and `Duration` use ISO-8601 strings, UUIDs use their canonical form.
 * Unknown properties are ignored by default so older code can read newer documents.
 *
 * Instances are immutable and thread-safe.
 */
public class JsonCodec private constructor(private val mapper: JsonMapper) {
    public fun encode(value: Any?): String = wrap { mapper.writeValueAsString(value) }

    public fun encodeToBytes(value: Any?): ByteArray = wrap { mapper.writeValueAsBytes(value) }

    public fun <T> decode(json: String, type: Class<T>): T = wrap { mapper.readValue(json, type) }

    public fun <T> decode(json: ByteArray, type: Class<T>): T = wrap { mapper.readValue(json, type) }

    public fun <T> decode(json: InputStream, type: Class<T>): T = wrap { mapper.readValue(json, type) }

    /** Decodes generic types: `codec.decode(json, object : TypeReference<Map<String, List<Item>>>() {})`. */
    public fun <T> decode(json: String, type: TypeReference<T>): T = wrap { mapper.readValue(json, type) }

    public fun <T> decodeList(json: String, elementType: Class<T>): List<T> =
        wrap { mapper.readValue(json, listType(elementType)) }

    public fun <V> decodeMap(json: String, valueType: Class<V>): Map<String, V> =
        wrap { mapper.readValue(json, mapper.typeFactory.constructMapType(LinkedHashMap::class.java, String::class.java, valueType)) }

    /** Converts between compatible representations, for example a `Map` into a data class. */
    public fun <T> convert(value: Any?, type: Class<T>): T = wrap { mapper.convertValue(value, type) }

    public fun toBuilder(): Builder = Builder(mapper.rebuild())

    private fun listType(elementType: Class<*>): JavaType = mapper.typeFactory.constructCollectionType(List::class.java, elementType)

    private inline fun <T> wrap(block: () -> T): T = try {
        block()
    } catch (exception: DatabindException) {
        val path = exception.path.joinToString("") { reference ->
            reference.propertyName?.let { ".$it" } ?: "[${reference.index}]"
        }.removePrefix(".").takeIf { it.isNotEmpty() }
        throw SerializationException(exception.originalMessage + (path?.let { " (at $it)" } ?: ""), path, exception)
    } catch (exception: JacksonException) {
        throw SerializationException(exception.originalMessage, null, exception)
    }

    public class Builder internal constructor(private val builder: JsonMapper.Builder) {
        private val module = SimpleModule("qavlen")

        public fun prettyPrint(enabled: Boolean): Builder = apply { builder.configure(SerializationFeature.INDENT_OUTPUT, enabled) }

        public fun failOnUnknownProperties(enabled: Boolean): Builder =
            apply { builder.configure(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES, enabled) }

        /** Stores [type] as a JSON string using the given conversions, for example a namespaced key. */
        public fun <T : Any> stringType(type: Class<T>, toText: Function<T, String>, fromText: Function<String, T>): Builder = apply {
            module.addSerializer(
                type,
                object : ValueSerializer<T>() {
                    override fun serialize(value: T, generator: JsonGenerator, context: SerializationContext) {
                        generator.writeString(toText.apply(value))
                    }
                },
            )
            module.addDeserializer(
                type,
                object : ValueDeserializer<T>() {
                    override fun deserialize(parser: JsonParser, context: DeserializationContext): T {
                        val text = parser.valueAsString ?: return type.cast(context.handleUnexpectedToken(type, parser))
                        return try {
                            fromText.apply(text)
                        } catch (exception: IllegalArgumentException) {
                            type.cast(context.handleWeirdStringValue(type, text, exception.message ?: "invalid value"))
                        }
                    }
                },
            )
        }

        /** Maps property names, for example `PropertyNamingStrategies.KEBAB_CASE` turns `poolSize` into `pool-size`. */
        public fun propertyNaming(strategy: PropertyNamingStrategy): Builder = apply { builder.propertyNamingStrategy(strategy) }

        /** Accepts enum constants regardless of case, so `fast` reads as `FAST`. */
        public fun caseInsensitiveEnums(
            enabled: Boolean,
        ): Builder = apply { builder.configure(MapperFeature.ACCEPT_CASE_INSENSITIVE_ENUMS, enabled) }

        /** Registers a Jackson module for full control over serialization. */
        public fun module(module: JacksonModule): Builder = apply { builder.addModule(module) }

        public fun build(): JsonCodec = JsonCodec(builder.addModule(module).build())
    }

    public companion object {
        /** A codec with Qavlen's defaults. */
        @JvmStatic
        public val default: JsonCodec by lazy { builder().build() }

        @JvmStatic
        public fun builder(): Builder = Builder(
            JsonMapper.builder()
                .addModule(KotlinModule.Builder().build())
                .disable(DateTimeFeature.WRITE_DATES_AS_TIMESTAMPS)
                .disable(DateTimeFeature.WRITE_DURATIONS_AS_TIMESTAMPS)
                .disable(DeserializationFeature.FAIL_ON_UNKNOWN_PROPERTIES)
                .enable(DeserializationFeature.FAIL_ON_NULL_FOR_PRIMITIVES),
        )
    }
}

/** Kotlin shorthand for generic types: `codec.decode<List<Item>>(json)`. */
public inline fun <reified T> JsonCodec.decode(json: String): T = decode(json, object : TypeReference<T>() {})
