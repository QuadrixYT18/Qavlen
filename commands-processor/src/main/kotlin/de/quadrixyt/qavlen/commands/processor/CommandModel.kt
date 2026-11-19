package de.quadrixyt.qavlen.commands.processor

/**
 * Language-neutral description of an annotated command class. The KSP and javac front ends fill it in,
 * [validate] checks it and [JavaEmitter] turns it into a descriptor.
 */
internal class CommandModel(
    val packageName: String,
    /** Qualified source name, `com.example.Outer.Inner` for nested classes. */
    val typeName: String,
    /** Simple names from the outermost class, joined with `_`. */
    val flatName: String,
    val name: String,
    val aliases: List<String>,
    val description: String,
    val permission: String?,
    val generic: Boolean,
    val methods: List<MethodModel>,
    val origin: Any?,
) {
    val descriptorName: String get() = flatName + DESCRIPTOR_SUFFIX
}

internal class MethodModel(
    val name: String,
    /** Subcommand path, or `null` for the `@Execute` method. */
    val path: List<String>?,
    val aliases: List<String>,
    val description: String,
    val permission: String?,
    val cooldownSeconds: Long?,
    val parameters: List<ParameterModel>,
    val returnsVoid: Boolean,
    val accessible: Boolean,
    /** A problem the front end found that cannot be expressed in the model, such as a suspend function. */
    val frontEndProblem: String?,
    val origin: Any?,
)

internal enum class ParameterKind { CONTEXT, SOURCE, SENDER, ARGUMENT }

internal enum class ValueKind { STRING, INT, LONG, DOUBLE, BOOLEAN, OTHER }

internal class ParameterModel(
    val kind: ParameterKind,
    val sourceName: String,
    val explicitName: String?,
    val description: String,
    /** Qualified source name of the boxed type, for example `java.lang.Integer`. */
    val typeName: String,
    val valueKind: ValueKind,
    val primitive: Boolean,
    val nullable: Boolean,
    val unsupportedReason: String?,
    val optional: Boolean,
    val greedy: Boolean,
    val range: ClosedFloatingPointRange<Double>?,
    val origin: Any?,
) {
    /** Argument name: explicit, or the parameter name converted from camelCase to kebab-case. */
    val argumentName: String
        get() = explicitName?.takeIf(String::isNotEmpty)
            ?: sourceName.replace(Regex("([a-z0-9])([A-Z])"), "$1-$2").lowercase()
}

internal class Problem(val message: String, val origin: Any?)

internal const val DESCRIPTOR_SUFFIX = "_QavlenCommand"
internal const val COMMANDS_PACKAGE = "de.quadrixyt.qavlen.commands"
internal const val ANNOTATIONS_PACKAGE = "$COMMANDS_PACKAGE.annotations"

private val label = Regex("[a-z0-9_-]{1,32}")

/** Checks everything that would otherwise only fail when the command is registered or executed. */
internal fun validate(model: CommandModel): List<Problem> {
    val problems = mutableListOf<Problem>()
    fun problem(message: String, origin: Any?) {
        problems += Problem(message, origin)
    }

    if (!label.matches(model.name)) problem("Command name '${model.name}' must be 1-32 lowercase letters, digits, '-' or '_'", model.origin)
    model.aliases.filterNot(label::matches).forEach {
        problem("Alias '$it' must be 1-32 lowercase letters, digits, '-' or '_'", model.origin)
    }
    if (model.generic) problem("Command classes must not have type parameters", model.origin)
    if (model.methods.isEmpty()) problem("@Command class '${model.typeName}' declares no @Subcommand or @Execute method", model.origin)

    val roots = model.methods.filter { it.path == null }
    if (roots.size > 1) roots.drop(1).forEach { problem("Only one method may be annotated with @Execute", it.origin) }

    model.methods.groupBy { it.path }.filterKeys { it != null }.values.filter { it.size > 1 }.forEach { duplicates ->
        duplicates.drop(1).forEach { problem("Subcommand '${it.path!!.joinToString(" ")}' is declared more than once", it.origin) }
    }

    val paths = model.methods.mapNotNull { it.path }
    for (method in model.methods) {
        method.frontEndProblem?.let { problem(it, method.origin) }
        method.path?.forEach { segment ->
            if (!label.matches(segment)) problem("Subcommand '$segment' must be 1-32 lowercase letters, digits, '-' or '_'", method.origin)
        }
        if (method.path?.isEmpty() == true) problem("@Subcommand needs a name; use @Execute for the root command", method.origin)
        method.aliases.filterNot(label::matches).forEach {
            problem("Alias '$it' must be 1-32 lowercase letters, digits, '-' or '_'", method.origin)
        }
        if (!method.accessible) problem("Command method '${method.name}' must be public", method.origin)
        if (!method.returnsVoid) problem("Command method '${method.name}' must not return a value", method.origin)
        if (method.cooldownSeconds != null && method.cooldownSeconds < 0) problem("@Cooldown must not be negative", method.origin)

        val own = method.path.orEmpty()
        val hasChildren = paths.any { it.size > own.size && it.subList(0, own.size) == own }
        val arguments = method.parameters.filter { it.kind == ParameterKind.ARGUMENT }
        if (hasChildren && arguments.isNotEmpty()) {
            problem(
                "'${method.name}' takes arguments but '${own.joinToString(" ").ifEmpty { model.name }}' also has subcommands; " +
                    "move the arguments to a subcommand",
                method.origin,
            )
        }
        validateParameters(method, arguments, ::problem)
    }
    return problems
}

private fun validateParameters(method: MethodModel, arguments: List<ParameterModel>, problem: (String, Any?) -> Unit) {
    if (method.parameters.count { it.kind == ParameterKind.SENDER } >
        1
    ) {
        problem("Only one parameter may be annotated with @Sender", method.origin)
    }
    method.parameters.filter { it.kind != ParameterKind.ARGUMENT }.forEach { parameter ->
        if (parameter.optional || parameter.greedy || parameter.range != null) {
            problem("'${parameter.sourceName}' is not an argument; @Optional, @Greedy and @Range do not apply", parameter.origin)
        }
    }
    arguments.forEachIndexed { index, argument ->
        val name = argument.argumentName
        argument.unsupportedReason?.let { problem("Argument '${argument.sourceName}' has an unsupported type: $it", argument.origin) }
        if (!label.matches(name)) problem("Argument name '$name' must be 1-32 lowercase letters, digits, '-' or '_'", argument.origin)
        if (argument.optional && argument.primitive) {
            problem("Optional argument '$name' must use a nullable or boxed type", argument.origin)
        }
        if (!argument.optional && argument.nullable) {
            problem("Argument '$name' is nullable; annotate it with @Optional or make it non-null", argument.origin)
        }
        if (!argument.optional && arguments.take(index).any { it.optional }) {
            problem("Required argument '$name' must come before optional arguments", argument.origin)
        }
        if (argument.greedy && argument.valueKind != ValueKind.STRING) problem("@Greedy requires a String argument", argument.origin)
        if (argument.greedy && index != arguments.lastIndex) problem("@Greedy is only allowed on the last argument", argument.origin)
        val range = argument.range
        if (range != null) {
            if (argument.valueKind !in setOf(ValueKind.INT, ValueKind.LONG, ValueKind.DOUBLE)) {
                problem("@Range requires an Int, Long or Double argument", argument.origin)
            }
            if (range.start > range.endInclusive) problem("@Range min must not exceed max", argument.origin)
        }
    }
    arguments.groupBy { it.argumentName }.filterValues { it.size > 1 }.keys.forEach {
        problem("Argument name '$it' is used more than once in '${method.name}'", method.origin)
    }
}
