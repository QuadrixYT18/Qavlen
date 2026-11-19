package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.JsonConfig
import de.quadrixyt.qavlen.config.YamlConfig
import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.comments.CommentLine
import org.yaml.snakeyaml.comments.CommentType
import org.yaml.snakeyaml.constructor.SafeConstructor
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import org.yaml.snakeyaml.representer.Representer
import org.yaml.snakeyaml.resolver.Resolver
import tools.jackson.core.JacksonException
import tools.jackson.databind.SerializationFeature
import tools.jackson.databind.json.JsonMapper
import tools.jackson.dataformat.toml.TomlMapper
import java.io.StringWriter

/**
 * File formats of typed configurations.
 *
 * - YAML supports everything, including comments from [Comment] and [Configuration.header].
 * - JSON has no comments.
 * - TOML has no `null`: properties set to `null` are left out and read back as their default.
 */
public enum class ConfigFileFormat {
    YAML {
        override fun read(text: String, source: String): Map<String, Any?> = YamlConfig.parse(text).toMap()

        override fun write(tree: Map<String, Any?>, model: ConfigModel?): String {
            if (tree.isEmpty()) return ""
            val dumper = DumperOptions().apply {
                defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
                indent = 2
                width = 4096
                isProcessComments = true
            }
            val yaml = Yaml(SafeConstructor(LoaderOptions()), Representer(dumper), dumper, LoaderOptions(), PlainTimestamps())
            val node = yaml.represent(tree)
            if (node is MappingNode && model != null) {
                comment(node, model)
                if (model.header.isNotEmpty()) {
                    val first = node.value.first().keyNode
                    first.blockComments = model.header.map(::line) + blank() + first.blockComments.orEmpty()
                }
            }
            // Blank separator lines inside nested sections would otherwise carry indentation.
            return StringWriter().also { yaml.serialize(node, it) }.toString().replace(blankIndentation, "")
        }
    },

    JSON {
        override fun read(text: String, source: String): Map<String, Any?> = JsonConfig.parse(text).toMap()

        override fun write(tree: Map<String, Any?>, model: ConfigModel?): String = json.writeValueAsString(tree) + "\n"
    },

    TOML {
        override fun read(text: String, source: String): Map<String, Any?> = try {
            @Suppress("UNCHECKED_CAST")
            (toml.readValue(text, Map::class.java) as Map<String, Any?>?).orEmpty()
        } catch (exception: JacksonException) {
            throw ConfigException("Invalid TOML in $source: ${exception.originalMessage}", exception)
        }

        override fun write(tree: Map<String, Any?>, model: ConfigModel?): String {
            nullPaths(tree).firstOrNull { it.endsWith("]") }?.let {
                throw ConfigException("TOML cannot store null list elements, found one at '$it'")
            }
            return toml.writeValueAsString(withoutNulls(tree))
        }
    }, ;

    internal abstract fun read(text: String, source: String): Map<String, Any?>

    internal abstract fun write(tree: Map<String, Any?>, model: ConfigModel?): String

    public companion object {
        /** Picks the format from a file extension: `.yml`, `.yaml`, `.json` or `.toml`. */
        @JvmStatic
        public fun fromFileName(name: String): ConfigFileFormat = when (name.substringAfterLast('.', "").lowercase()) {
            "yml", "yaml" -> YAML
            "json" -> JSON
            "toml" -> TOML
            else -> throw IllegalArgumentException("Cannot infer the configuration format of '$name'; pass it explicitly")
        }

        private val blankIndentation = Regex("(?m)^[ \\t]+$")
        private val json = JsonMapper.builder().enable(SerializationFeature.INDENT_OUTPUT).build()
        private val toml = TomlMapper.builder().build()

        private fun comment(node: MappingNode, model: ConfigModel) {
            node.value.forEachIndexed { index, tuple ->
                val key = (tuple.keyNode as? ScalarNode)?.value ?: return@forEachIndexed
                val property = model.property(key) ?: return@forEachIndexed
                if (property.comment.isNotEmpty()) {
                    tuple.keyNode.blockComments = (if (index > 0) listOf(blank()) else emptyList()) + property.comment.map(::line)
                }
                val value = tuple.valueNode
                when {
                    value is MappingNode && property.nested != null -> comment(value, property.nested)
                    value is MappingNode && property.element != null ->
                        value.value.forEach { entry -> (entry.valueNode as? MappingNode)?.let { comment(it, property.element) } }
                    value is SequenceNode && property.element != null ->
                        value.value.forEach { element -> (element as? MappingNode)?.let { comment(it, property.element) } }
                }
            }
        }

        private fun line(text: String) = CommentLine(null, null, if (text.isEmpty()) "" else " $text", CommentType.BLOCK)

        private fun blank() = CommentLine(null, null, "", CommentType.BLANK_LINE)

        /** Paths of `null` values, which TOML cannot store; list elements are reported as `path[index]`. */
        internal fun nullPaths(value: Any?, path: String = ""): List<String> = when (value) {
            null -> listOf(path)
            is Map<*, *> -> value.flatMap { (key, entry) -> nullPaths(entry, if (path.isEmpty()) "$key" else "$path.$key") }
            is List<*> -> value.flatMapIndexed { index, entry -> nullPaths(entry, "$path[$index]") }
            else -> emptyList()
        }

        @Suppress("UNCHECKED_CAST")
        private fun withoutNulls(tree: Map<String, Any?>): Map<String, Any?> = tree.filterValues { it != null }.mapValues { (_, value) ->
            if (value is Map<*, *>) withoutNulls(value as Map<String, Any?>) else value
        }
    }
}

/** Keeps dates as written: SnakeYAML would otherwise quote strings such as `2025-01-01` when writing them. */
private class PlainTimestamps : Resolver() {
    override fun addImplicitResolver(tag: Tag, regexp: java.util.regex.Pattern, first: String?) {
        if (tag != Tag.TIMESTAMP) super.addImplicitResolver(tag, regexp, first)
    }

    override fun addImplicitResolver(tag: Tag, regexp: java.util.regex.Pattern, first: String?, limit: Int) {
        if (tag != Tag.TIMESTAMP) super.addImplicitResolver(tag, regexp, first, limit)
    }
}
