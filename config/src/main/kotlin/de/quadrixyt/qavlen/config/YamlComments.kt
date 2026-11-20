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
import java.io.StringReader
import java.io.StringWriter
import org.yaml.snakeyaml.nodes.Node as YamlNode

/**
 * Returns [updated] with the comments of [previous] carried over: comments above a key, comments after a value on
 * the same line, blank lines and comments at the end of a section or file, matched by key path. Keys that only
 * exist in [updated] keep their own comments; comments of removed keys are dropped.
 *
 * Both texts must be valid YAML. When either cannot be read, [updated] is returned unchanged.
 */
public fun preserveYamlComments(previous: String, updated: String): String {
    val hasComments = '#' in previous || "\n\n" in previous
    if (!hasComments || updated.isBlank()) return updated
    val old = compose(previous) ?: return updated
    val new = compose(updated) ?: return updated
    transfer(old, new)
    return serialize(new) ?: updated
}

/**
 * Like [preserveYamlComments], and additionally gives keys that only [bundled] defines, such as settings a new plugin
 * version added, the comments they have in [bundled]. Keys that already exist in [previous] keep exactly the comments
 * written there, so comments a person removed do not come back.
 */
internal fun preserveYamlComments(previous: String, updated: String, bundled: String): String {
    val kept = preserveYamlComments(previous, updated)
    if ('#' !in bundled) return kept
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
    for (tuple in new.value) {
        val source = defaults[key(tuple)] ?: continue
        val match = existing[key(tuple)]
        if (match != null) {
            addNewKeyComments(source.valueNode, tuple.valueNode, match.valueNode)
            continue
        }
        tuple.keyNode.blockComments = source.keyNode.blockComments
        tuple.keyNode.inLineComments = source.keyNode.inLineComments
        transfer(source.valueNode, tuple.valueNode)
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

private fun transfer(old: YamlNode, new: YamlNode) {
    new.blockComments = old.blockComments ?: new.blockComments
    new.inLineComments = old.inLineComments ?: new.inLineComments
    new.endComments = old.endComments ?: new.endComments
    when {
        old is MappingNode && new is MappingNode -> {
            val oldTuples = old.value.associateBy(::key)
            for (tuple in new.value) {
                val match = oldTuples[key(tuple)] ?: continue
                tuple.keyNode.blockComments = match.keyNode.blockComments
                tuple.keyNode.inLineComments = match.keyNode.inLineComments
                transfer(match.valueNode, tuple.valueNode)
            }
        }
        old is SequenceNode && new is SequenceNode -> old.value.zip(new.value).forEach { (before, after) -> transfer(before, after) }
    }
}
