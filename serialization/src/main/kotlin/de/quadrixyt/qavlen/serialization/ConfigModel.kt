package de.quadrixyt.qavlen.serialization

import com.fasterxml.jackson.annotation.JsonProperty
import de.quadrixyt.qavlen.config.ConfigViolation
import java.lang.reflect.Modifier
import java.lang.reflect.ParameterizedType
import java.lang.reflect.Type
import java.time.Duration
import java.time.temporal.TemporalAccessor
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.reflect.full.primaryConstructor
import kotlin.reflect.jvm.javaType

/**
 * Properties of a configuration class as they appear in files: names after the codec's naming strategy, comments
 * and validation annotations, and the model of nested classes. Built once per class by reading constructor
 * parameters (Kotlin classes and records) or fields (Java beans).
 */
internal class ConfigModel private constructor(val type: Class<*>, val header: List<String>) {
    lateinit var properties: List<ConfigProperty>
        private set

    fun property(name: String): ConfigProperty? = properties.firstOrNull { it.name == name }

    /** Leaf paths of scalar properties, which environment variables and system properties can override. */
    fun scalarPaths(prefix: String = ""): List<String> = properties.flatMap { property ->
        val path = join(prefix, property.name)
        when {
            property.nested != null -> property.nested.scalarPaths(path)
            property.collection -> emptyList()
            else -> listOf(path)
        }
    }

    /** Checks all annotations against [tree], the serialized value, and collects every violation. */
    fun validate(tree: Map<*, *>, prefix: String, violations: MutableList<ConfigViolation>) {
        for (property in properties) {
            val path = join(prefix, property.name)
            val present = tree.containsKey(property.name)
            val value = tree[property.name]
            property.check(present, value)?.let { violations += ConfigViolation(path, it) }
            when {
                value is Map<*, *> && property.nested != null -> property.nested.validate(value, path, violations)
                value is List<*> && property.element != null -> value.forEachIndexed { index, element ->
                    if (element is Map<*, *>) property.element.validate(element, "$path[$index]", violations)
                }
                value is Map<*, *> && property.element != null -> value.forEach { (key, element) ->
                    if (element is Map<*, *>) property.element.validate(element, "$path.$key", violations)
                }
            }
        }
    }

    companion object {
        private val cache = ConcurrentHashMap<Pair<Class<*>, JsonCodec>, ConfigModel>()

        /** The model of [type], or `null` when it is not a structured class with properties. */
        fun of(type: Class<*>, codec: JsonCodec): ConfigModel? {
            if (!isStructured(type)) return null
            cache[type to codec]?.let { return it }
            val model = ConfigModel(type, type.getAnnotation(Configuration::class.java)?.header?.toList().orEmpty())
            cache[type to codec] = model
            model.properties = declaredProperties(type).map { (name, annotations, genericType) ->
                val jsonName = annotations.filterIsInstance<JsonProperty>().firstOrNull()?.value?.takeIf(String::isNotEmpty)
                ConfigProperty(
                    name = jsonName ?: codec.propertyName(name),
                    annotations = annotations,
                    nested = of(raw(genericType), codec),
                    element = elementType(genericType)?.let { of(raw(it), codec) },
                    collection = isCollection(raw(genericType)),
                )
            }
            return model
        }

        private data class Declared(val name: String, val annotations: List<Annotation>, val type: Type)

        private fun declaredProperties(type: Class<*>): List<Declared> {
            if (type.isAnnotationPresent(Metadata::class.java)) {
                type.kotlin.primaryConstructor?.let { constructor ->
                    return constructor.parameters.mapNotNull { parameter ->
                        Declared(parameter.name ?: return@mapNotNull null, parameter.annotations, parameter.type.javaType)
                    }
                }
            }
            if (type.isRecord) {
                val components = type.recordComponents
                val constructor = type.getDeclaredConstructor(*components.map { it.type }.toTypedArray())
                return components.mapIndexed { index, component ->
                    Declared(component.name, constructor.parameterAnnotations[index].toList(), component.genericType)
                }
            }
            return generateSequence(type) { it.superclass }.takeWhile { it != Any::class.java }.toList().reversed()
                .flatMap { it.declaredFields.toList() }
                .filter { !Modifier.isStatic(it.modifiers) && !Modifier.isTransient(it.modifiers) && !it.isSynthetic }
                .map { Declared(it.name, it.annotations.toList(), it.genericType) }
        }

        private fun raw(type: Type): Class<*> = when (type) {
            is Class<*> -> type
            is ParameterizedType -> type.rawType as Class<*>
            else -> Any::class.java
        }

        private fun elementType(type: Type): Type? {
            if (type is Class<*> && type.isArray) return type.componentType
            if (type !is ParameterizedType) return null
            val raw = raw(type)
            return when {
                Map::class.java.isAssignableFrom(raw) -> type.actualTypeArguments.getOrNull(1)
                Collection::class.java.isAssignableFrom(raw) -> type.actualTypeArguments.firstOrNull()
                else -> null
            }?.let { if (it is java.lang.reflect.WildcardType) it.upperBounds.first() else it }
        }

        private fun isCollection(type: Class<*>) =
            type.isArray || Collection::class.java.isAssignableFrom(type) || Map::class.java.isAssignableFrom(type)

        private fun isStructured(type: Class<*>): Boolean = !type.isPrimitive &&
            !type.isEnum &&
            !type.isArray &&
            !type.isInterface &&
            !isCollection(type) &&
            type != Any::class.java &&
            !type.name.startsWith("java.") &&
            !type.name.startsWith("javax.") &&
            !type.name.startsWith("kotlin.") &&
            !Number::class.java.isAssignableFrom(type) &&
            !CharSequence::class.java.isAssignableFrom(type) &&
            !TemporalAccessor::class.java.isAssignableFrom(type) &&
            type != UUID::class.java &&
            type != Duration::class.java

        private fun join(prefix: String, name: String) = if (prefix.isEmpty()) name else "$prefix.$name"
    }
}

internal class ConfigProperty(
    val name: String,
    val annotations: List<Annotation>,
    val nested: ConfigModel?,
    val element: ConfigModel?,
    val collection: Boolean,
) {
    val comment: List<String> = annotations.filterIsInstance<Comment>().flatMap { it.value.toList() }

    /** The first violated annotation, described for the user, or `null`. */
    fun check(present: Boolean, value: Any?): String? {
        for (annotation in annotations) {
            val problem = when (annotation) {
                is Required -> if (!present || value == null) "is required" else null
                is NotBlank -> if (value == null || value is String && value.isBlank()) "must not be blank" else null
                is Range -> (value as? Number)?.toDouble()?.takeUnless { it in annotation.min..annotation.max }?.let {
                    "expected value between ${format(annotation.min)} and ${format(annotation.max)}, got $value"
                }
                is Length -> size(value)?.takeUnless { it in annotation.min..annotation.max }?.let {
                    "expected length between ${annotation.min} and ${annotation.max}, got $it"
                }
                is Pattern -> (value as? String)?.takeUnless { Regex(annotation.regex).matches(it) }?.let {
                    "must match ${annotation.regex}, got '$it'"
                }
                else -> null
            }
            if (problem != null) return problem
        }
        return null
    }

    private fun size(value: Any?): Int? = when (value) {
        is String -> value.length
        is Collection<*> -> value.size
        is Map<*, *> -> value.size
        else -> null
    }

    private fun format(value: Double): String = when {
        value == -Double.MAX_VALUE -> "-infinity"
        value == Double.MAX_VALUE -> "infinity"
        value % 1.0 == 0.0 -> value.toLong().toString()
        else -> value.toString()
    }
}
