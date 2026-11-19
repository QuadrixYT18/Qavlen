package de.quadrixyt.qavlen.config

import com.google.gson.GsonBuilder
import com.google.gson.Strictness
import com.google.gson.stream.JsonReader
import com.google.gson.stream.JsonToken
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.representer.Representer
import java.io.StringReader
import java.math.BigDecimal
import java.math.BigInteger
import java.util.Date
import java.util.UUID

internal typealias Node = MutableMap<String, Any?>

internal object Missing

internal fun segments(path: String): List<String> {
    val parts = path.split('.')
    require(path.isNotEmpty() && parts.none(String::isEmpty)) { "Invalid configuration path '$path'" }
    return parts
}

internal fun join(prefix: String, key: String): String = if (prefix.isEmpty()) key else "$prefix.$key"

internal fun lookup(root: Node, path: List<String>): Any? {
    var current: Any? = root
    for (segment in path) {
        val section = current as? Map<*, *> ?: return Missing
        if (!section.containsKey(segment)) return Missing
        current = section[segment]
    }
    return current
}

@Suppress("UNCHECKED_CAST")
internal fun put(root: Node, path: List<String>, value: Any?) {
    var section = root
    path.dropLast(1).forEachIndexed { index, segment ->
        section = when (val child = section[segment]) {
            null -> linkedMapOf<String, Any?>().also { section[segment] = it }
            is MutableMap<*, *> -> child as Node
            else -> throw ConfigException(
                "Cannot set '${path.joinToString(".")}': '${path.take(index + 1).joinToString(".")}' is not a section",
            )
        }
    }
    section[path.last()] = normalize(value)
}

@Suppress("UNCHECKED_CAST")
internal fun delete(root: Node, path: List<String>): Boolean {
    val parent = if (path.size == 1) root else lookup(root, path.dropLast(1)) as? MutableMap<String, Any?> ?: return false
    if (!parent.containsKey(path.last())) return false
    parent.remove(path.last())
    return true
}

/** Copies maps and lists into mutable structures and narrows parser-specific number and date types. */
internal fun normalize(value: Any?): Any? = when (value) {
    null, is String, is Boolean, is Int, is Long, is Double -> value
    is Map<*, *> -> value.entries.associateTo(linkedMapOf<String, Any?>()) { (key, entry) ->
        (key as? String ?: key?.toString() ?: throw ConfigException("Configuration keys must not be null")) to normalize(entry)
    }
    is Iterable<*> -> value.mapTo(ArrayList(), ::normalize)
    is Array<*> -> value.mapTo(ArrayList(), ::normalize)
    is Short, is Byte -> (value as Number).toInt()
    is Float -> value.toDouble()
    is BigInteger -> if (value.bitLength() < 64) narrow(value.toString()) else value
    is BigDecimal -> value.toDouble().takeIf { it.isFinite() && BigDecimal(it.toString()).compareTo(value) == 0 } ?: value
    is Number -> narrow(value.toString())
    is CharSequence, is UUID -> value.toString()
    is Enum<*> -> value.name
    is Date -> value.toInstant().toString()
    else -> throw ConfigException("Unsupported configuration value of type ${value.javaClass.name}")
}

/** Picks the smallest exact representation: Int, Long, BigInteger, Double, or BigDecimal when a Double would round. */
private fun narrow(number: String): Any {
    number.toLongOrNull()?.let { return if (it in Int.MIN_VALUE..Int.MAX_VALUE) it.toInt() else it }
    if (number.none { it == '.' || it == 'e' || it == 'E' }) return BigInteger(number)
    return normalize(BigDecimal(number))!!
}

@Suppress("UNCHECKED_CAST")
internal fun deepCopy(node: Map<String, Any?>): Node = normalize(node) as Node

internal fun leafPaths(node: Map<String, Any?>, prefix: String = ""): Set<String> = buildSet {
    for ((key, value) in node) {
        val path = join(prefix, key)
        if (value is Map<*, *> && value.isNotEmpty()) {
            @Suppress("UNCHECKED_CAST")
            addAll(leafPaths(value as Map<String, Any?>, path))
        } else {
            add(path)
        }
    }
}

/**
 * Adds every default that is missing from [target] and returns the added paths. Existing values are never
 * replaced; a key that is a section on one side and a value on the other is reported as a conflict.
 */
@Suppress("UNCHECKED_CAST")
internal fun mergeDefaults(target: Node, defaults: Map<String, Any?>, prefix: String = ""): List<String> {
    val added = mutableListOf<String>()
    for ((key, defaultValue) in defaults) {
        val path = join(prefix, key)
        if (!target.containsKey(key)) {
            target[key] = normalize(defaultValue)
            added += path
            continue
        }
        val current = target[key]
        val currentIsSection = current is Map<*, *>
        val defaultIsSection = defaultValue is Map<*, *>
        when {
            currentIsSection && defaultIsSection -> added += mergeDefaults(current as Node, defaultValue as Map<String, Any?>, path)
            currentIsSection != defaultIsSection && current != null -> throw ConfigException(
                "Key conflict at '$path': the file contains ${if (currentIsSection) "a section" else "a value"} " +
                    "where the defaults define ${if (defaultIsSection) "a section" else "a value"}",
            )
        }
    }
    return added
}

/** File formats understood by [ConfigFile]. */
public enum class ConfigFormat {
    YAML {
        override fun decode(text: String, source: String): Node {
            val root = try {
                yaml().load<Any?>(text)
            } catch (exception: YAMLException) {
                throw ConfigException("Invalid YAML in $source: ${exception.message}", exception)
            }
            return root(root, source)
        }

        override fun encode(root: Map<String, Any?>): String = if (root.isEmpty()) "" else yaml().dump(root)
    },

    JSON {
        override fun decode(text: String, source: String): Node {
            if (text.isBlank()) return linkedMapOf()
            val reader = JsonReader(StringReader(text)).apply { strictness = Strictness.STRICT }
            val root = try {
                readJson(reader, "").also {
                    if (reader.peek() != JsonToken.END_DOCUMENT) throw ConfigException("Unexpected content after the JSON document")
                }
            } catch (exception: java.io.IOException) {
                throw ConfigException("Invalid JSON in $source: ${exception.message}", exception)
            } catch (exception: IllegalStateException) {
                throw ConfigException("Invalid JSON in $source: ${exception.message}", exception)
            }
            return root(root, source)
        }

        override fun encode(root: Map<String, Any?>): String = gson.toJson(root) + "\n"
    }, ;

    internal abstract fun decode(text: String, source: String): Node

    internal abstract fun encode(root: Map<String, Any?>): String

    public companion object {
        /** Picks a format from a file extension (`.yml`, `.yaml` or `.json`). */
        @JvmStatic
        public fun fromFileName(name: String): ConfigFormat = when (name.substringAfterLast('.', "").lowercase()) {
            "yml", "yaml" -> YAML
            "json" -> JSON
            else -> throw IllegalArgumentException("Cannot infer configuration format of '$name'")
        }
    }
}

private val gson = GsonBuilder().setPrettyPrinting().serializeNulls().disableHtmlEscaping().create()

private fun root(value: Any?, source: String): Node = when (value) {
    null -> linkedMapOf()
    is Map<*, *> -> deepCopy(value.entries.associate { (key, entry) -> key.toString() to entry })
    else -> throw ConfigException("The root of $source must be a section, not ${value.javaClass.simpleName}")
}

private fun yaml(): Yaml {
    val loader = LoaderOptions().apply {
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 50
        codePointLimit = 8 * 1024 * 1024
    }
    val dumper = DumperOptions().apply {
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        indicatorIndent = 0
        width = 4096
        isProcessComments = false
    }
    return Yaml(SafeConstructor(loader), Representer(dumper), dumper, loader)
}

private fun readJson(reader: JsonReader, path: String): Any? = when (reader.peek()) {
    JsonToken.BEGIN_OBJECT -> {
        val result = linkedMapOf<String, Any?>()
        reader.beginObject()
        while (reader.hasNext()) {
            val key = reader.nextName()
            if (result.containsKey(key)) throw ConfigException("Duplicate JSON key '${join(path, key)}'")
            result[key] = readJson(reader, join(path, key))
        }
        reader.endObject()
        result
    }
    JsonToken.BEGIN_ARRAY -> {
        val result = ArrayList<Any?>()
        reader.beginArray()
        while (reader.hasNext()) result += readJson(reader, path)
        reader.endArray()
        result
    }
    JsonToken.NUMBER -> narrow(reader.nextString())
    JsonToken.BOOLEAN -> reader.nextBoolean()
    JsonToken.NULL -> reader.nextNull().let { null }
    else -> reader.nextString()
}
