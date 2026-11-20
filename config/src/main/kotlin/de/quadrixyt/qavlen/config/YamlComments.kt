@file:JvmName("YamlComments")

package de.quadrixyt.qavlen.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
import org.yaml.snakeyaml.nodes.NodeTuple
import org.yaml.snakeyaml.nodes.ScalarNode
import org.yaml.snakeyaml.nodes.SequenceNode
import org.yaml.snakeyaml.nodes.Tag
import java.io.StringReader
import java.io.StringWriter
import org.yaml.snakeyaml.nodes.Node as YamlNode

/**
 * Returns [updated] with the comments of [previous] carried over: comments above a key, comments after a value on
 * the same line, blank lines and comments at the end of a section or file, matched by key path. Text values and keys
 * keep the quotes they had in [previous] (`"text"`, `'text'`, or a `|` block). Keys that only exist in [updated] keep
 * their own comments; comments of removed keys are dropped.
 *
 * Both texts must be valid YAML. When either cannot be read, [updated] is returned unchanged.
 */
public fun preserveYamlComments(previous: String, updated: String): String {
    if (updated.isBlank()) return updated
    val old = compose(previous) ?: return updated
    val new = compose(updated) ?: return updated
    transfer(old, new)
    return serialize(new) ?: updated
}

/**
 * Like [preserveYamlComments], and additionally gives keys that only [bundled] defines, such as settings a new plugin
 * version added, the comments and quotes they have in [bundled]. Keys that already exist in [previous] keep exactly the comments
 * written there, so comments a person removed do not come back.
 */
public fun preserveYamlComments(previous: String, updated: String, bundled: String): String {
    val kept = preserveYamlComments(previous, updated)
    val user = compose(previous) ?: return kept
    val defaults = compose(bundled) ?: return kept
    val new = compose(kept) ?: return kept
    addNewKeyComments(defaults, new, user)
    return serialize(new) ?: kept
}

private fun addNewKeyComments(bundled: YamlNode, new: YamlNode, user: YamlNode?) {
    if (bundled !is MappingNode || new !is MappingNode) return
    val existing = (user as? MappingNode)?.value?.associateBy { key(it) }.orEmpty()
    val defaults = bundled.value.associateBy { key(it) }
    new.value.replaceAll { tuple ->
        val source = defaults[key(tuple)] ?: return@replaceAll tuple
        val match = existing[key(tuple)]
        if (match != null) {
            addNewKeyComments(source.valueNode, tuple.valueNode, match.valueNode)
            return@replaceAll tuple
        }
        // New keys take the comments and quotes of the resource; a key it does not comment keeps its own comment,
        // for example from @Comment.
        val keyNode = restyled(source.keyNode, tuple.keyNode)
        keyNode.blockComments = source.keyNode.blockComments ?: tuple.keyNode.blockComments
        keyNode.inLineComments = source.keyNode.inLineComments ?: tuple.keyNode.inLineComments
        NodeTuple(keyNode, transfer(source.valueNode, tuple.valueNode))
    }
}

private fun key(tuple: NodeTuple): String? = (tuple.keyNode as? ScalarNode)?.value

private fun serialize(node: YamlNode): String? = try {
    // Blank lines inside sections would otherwise keep the indentation of their section.
    StringWriter().also { yaml.serialize(node, it) }.toString().replace(blankIndentation, "")
} catch (_: YAMLException) {
    null
}

private val blankIndentation = Regex("(?m)^[ \\t]+$")

private val yaml: Yaml = run {
    val loader = LoaderOptions().apply {
        isProcessComments = true
        isAllowDuplicateKeys = false
        maxAliasesForCollections = 50
        codePointLimit = 8 * 1024 * 1024
    }
    val dumper = DumperOptions().apply {
        isProcessComments = true
        defaultFlowStyle = DumperOptions.FlowStyle.BLOCK
        indent = 2
        width = 4096
    }
    Yaml(org.yaml.snakeyaml.constructor.SafeConstructor(loader), org.yaml.snakeyaml.representer.Representer(dumper), dumper, loader)
}

private fun compose(text: String): YamlNode? = try {
    yaml.compose(StringReader(text))
} catch (_: YAMLException) {
    null
}

/**
 * Copies the comments of [old] onto [new], and for text values their quote style, so a value written as `"text"` or
 * `'text'` keeps its quotes. Returns [new], or a copy of it when its style changed.
 */
private fun transfer(old: YamlNode, new: YamlNode): YamlNode {
    val target = restyled(old, new)
    target.blockComments = old.blockComments ?: new.blockComments
    target.inLineComments = old.inLineComments ?: new.inLineComments
    target.endComments = old.endComments ?: new.endComments
    when {
        old is MappingNode && target is MappingNode -> {
            val oldTuples = old.value.associateBy(::key)
            target.value.replaceAll { tuple ->
                val match = oldTuples[key(tuple)] ?: return@replaceAll tuple
                val keyNode = restyled(match.keyNode, tuple.keyNode)
                keyNode.blockComments = match.keyNode.blockComments
                keyNode.inLineComments = match.keyNode.inLineComments
                NodeTuple(keyNode, transfer(match.valueNode, tuple.valueNode))
            }
        }
        old is SequenceNode && target is SequenceNode ->
            for (index in 0 until minOf(old.value.size, target.value.size)) {
                target.value[index] = transfer(old.value[index], target.value[index])
            }
    }
    return target
}

/**
 * [new] in the quote or block style [old] was written in, when both are text and [old] was not plain. A value that
 * needs quotes keeps them; the emitter falls back to another style when a value cannot be written in this one.
 */
private fun isText(old: ScalarNode, new: ScalarNode): Boolean = old.tag == Tag.STR && new.tag == Tag.STR

private fun restyled(old: YamlNode, new: YamlNode): YamlNode {
    if (old !is ScalarNode || new !is ScalarNode || !isText(old, new)) return new
    if (old.scalarStyle == DumperOptions.ScalarStyle.PLAIN || old.scalarStyle == new.scalarStyle) return new
    return ScalarNode(new.tag, new.value, new.startMark, new.endMark, old.scalarStyle).also {
        it.blockComments = new.blockComments
        it.inLineComments = new.inLineComments
        it.endComments = new.endComments
    }
}
