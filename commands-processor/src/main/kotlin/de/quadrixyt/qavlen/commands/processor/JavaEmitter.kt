package de.quadrixyt.qavlen.commands.processor

/** Generates the Java source of a command descriptor. Output is deterministic for reproducible builds. */
internal object JavaEmitter {
    private const val DEFINITION = "$COMMANDS_PACKAGE.CommandDefinition"
    private const val TYPES = "$COMMANDS_PACKAGE.ArgumentTypes"

    fun emit(model: CommandModel): String = buildString {
        if (model.packageName.isNotEmpty()) append("package ").append(model.packageName).append(";\n\n")
        append("@javax.annotation.processing.Generated(\"de.quadrixyt.qavlen.commands.processor\")\n")
        append("public final class ").append(model.descriptorName)
        append(" implements $COMMANDS_PACKAGE.CommandDescriptor<").append(model.typeName).append("> {\n")
        append("    @Override\n")
        append("    public java.lang.Class<").append(model.typeName).append("> getCommandType() {\n")
        append("        return ").append(model.typeName).append(".class;\n")
        append("    }\n\n")
        append("    @Override\n")
        append(
            "    public $DEFINITION create(",
        ).append(model.typeName).append(" command, $COMMANDS_PACKAGE.ArgumentTypeRegistry types) {\n")
        append("        return ")
        appendNode(tree(model), 2)
        append(";\n    }\n}\n")
    }

    private class Node(val name: String) {
        var aliases: List<String> = emptyList()
        var description = ""
        var permission: String? = null
        var conditions: List<ConditionModel> = emptyList()
        var method: MethodModel? = null
        val children = linkedMapOf<String, Node>()
    }

    private fun tree(model: CommandModel): Node {
        val root = Node(model.name).apply {
            aliases = model.aliases
            description = model.description
            permission = model.permission
            conditions = model.conditions
        }
        for (method in model.methods) {
            val path = method.path
            if (path == null) {
                root.method = method
                root.permission = model.permission ?: method.permission
                root.conditions = model.conditions + method.conditions
                continue
            }
            var node = root
            path.forEach { segment -> node = node.children.getOrPut(segment) { Node(segment) } }
            node.method = method
            node.aliases = method.aliases
            node.description = method.description
            node.permission = method.permission
            node.conditions = method.conditions
        }
        return root
    }

    private fun StringBuilder.appendNode(node: Node, depth: Int) {
        val indent = "    ".repeat(depth + 1)
        append("$DEFINITION.builder(").append(literal(node.name)).append(")")
        if (node.aliases.isNotEmpty()) {
            newline(
                indent,
            ).append(".aliases(").append(node.aliases.joinToString(", ", transform = ::literal)).append(")")
        }
        if (node.description.isNotEmpty()) newline(indent).append(".description(").append(literal(node.description)).append(")")
        node.permission?.let { newline(indent).append(".permission(").append(literal(it)).append(")") }
        node.conditions.forEach { condition ->
            val instance = if (condition.singleton) "${condition.typeName}.INSTANCE" else "new ${condition.typeName}()"
            newline(indent).append(".condition(").append(instance).append(")")
        }
        val method = node.method
        if (method != null) {
            method.cooldownSeconds?.takeIf { it > 0 }?.let { newline(indent).append(".cooldown(java.time.Duration.ofSeconds(${it}L))") }
            method.parameters.filter { it.kind == ParameterKind.ARGUMENT }.forEach { argument ->
                newline(indent).append(if (argument.optional) ".optionalArgument(" else ".argument(")
                append(literal(argument.argumentName)).append(", ").append(argumentType(argument)).append(", ")
                append(literal(argument.description)).append(")")
            }
            newline(indent).append(".executes(context -> command.").append(method.name).append("(")
            append(method.parameters.joinToString(", ", transform = ::parameterValue)).append("))")
        }
        for (child in node.children.values) {
            newline(indent).append(".subcommand(")
            appendNode(child, depth + 1)
            append(")")
        }
        newline(indent).append(".build()")
    }

    private fun StringBuilder.newline(indent: String): StringBuilder = append('\n').append(indent)

    private fun parameterValue(parameter: ParameterModel): String = when (parameter.kind) {
        ParameterKind.CONTEXT -> "context"
        ParameterKind.SOURCE -> "context.getSource()"
        ParameterKind.SENDER -> "context.sender(${parameter.typeName}.class)"
        ParameterKind.ARGUMENT -> {
            val accessor = if (parameter.optional) "argumentOrNull" else "argument"
            "context.$accessor(${literal(parameter.argumentName)}, ${parameter.typeName}.class)"
        }
    }

    private fun argumentType(parameter: ParameterModel): String {
        parameter.length?.let { length ->
            val factory = if (parameter.greedy) "greedyString" else "word"
            return "$TYPES.$factory(${length.first}, ${length.last})"
        }
        if (parameter.greedy) return "$TYPES.greedyString()"
        val range = parameter.range ?: return "types.resolve(${parameter.typeName}.class)"
        return when (parameter.valueKind) {
            ValueKind.INT -> "$TYPES.integer(${clampInt(range.start)}, ${clampInt(range.endInclusive)})"
            ValueKind.LONG -> "$TYPES.longNumber(${clampLong(range.start)}L, ${clampLong(range.endInclusive)}L)"
            else -> "$TYPES.decimal(${doubleLiteral(range.start)}, ${doubleLiteral(range.endInclusive)})"
        }
    }

    private fun clampInt(value: Double): Int = value.coerceIn(Int.MIN_VALUE.toDouble(), Int.MAX_VALUE.toDouble()).toInt()

    private fun clampLong(value: Double): Long = value.coerceIn(Long.MIN_VALUE.toDouble(), Long.MAX_VALUE.toDouble()).toLong()

    private fun doubleLiteral(value: Double): String = when (value) {
        -Double.MAX_VALUE -> "-java.lang.Double.MAX_VALUE"
        Double.MAX_VALUE -> "java.lang.Double.MAX_VALUE"
        else -> "${value}d"
    }

    fun literal(value: String): String = buildString {
        append('"')
        for (char in value) {
            when (char) {
                '"' -> append("\\\"")
                '\\' -> append("\\\\")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (char < ' ') append("\\u%04x".format(char.code)) else append(char)
            }
        }
        append('"')
    }
}
