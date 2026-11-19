@file:JvmName("YamlComments")

package de.quadrixyt.qavlen.config

import org.yaml.snakeyaml.DumperOptions
import org.yaml.snakeyaml.LoaderOptions
import org.yaml.snakeyaml.Yaml
import org.yaml.snakeyaml.error.YAMLException
import org.yaml.snakeyaml.nodes.MappingNode
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
    return try {
        // Blank lines inside sections would otherwise keep the indentation of their section.
        StringWriter().also { yaml.serialize(new, it) }.toString().replace(blankIndentation, "")
    } catch (_: YAMLException) {
        updated
    }
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
            val oldTuples = old.value.associateBy { (it.keyNode as? ScalarNode)?.value }
            for (tuple in new.value) {
                val match = oldTuples[(tuple.keyNode as? ScalarNode)?.value] ?: continue
                tuple.keyNode.blockComments = match.keyNode.blockComments
                tuple.keyNode.inLineComments = match.keyNode.inLineComments
                transfer(match.valueNode, tuple.valueNode)
            }
        }
        old is SequenceNode && new is SequenceNode -> old.value.zip(new.value).forEach { (before, after) -> transfer(before, after) }
    }
}
