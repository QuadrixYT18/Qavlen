package de.quadrixyt.qavlen.commands.processor

import com.google.devtools.ksp.ApiFeatures
import com.google.devtools.ksp.buildApiFeatures
import com.google.devtools.ksp.isPrivate
import com.google.devtools.ksp.isPublic
import com.google.devtools.ksp.processing.CodeGenerator
import com.google.devtools.ksp.processing.Dependencies
import com.google.devtools.ksp.processing.KSPLogger
import com.google.devtools.ksp.processing.Resolver
import com.google.devtools.ksp.processing.SymbolProcessor
import com.google.devtools.ksp.processing.SymbolProcessorEnvironment
import com.google.devtools.ksp.processing.SymbolProcessorProvider
import com.google.devtools.ksp.symbol.ClassKind
import com.google.devtools.ksp.symbol.KSAnnotated
import com.google.devtools.ksp.symbol.KSAnnotation
import com.google.devtools.ksp.symbol.KSClassDeclaration
import com.google.devtools.ksp.symbol.KSFunctionDeclaration
import com.google.devtools.ksp.symbol.KSNode
import com.google.devtools.ksp.symbol.KSType
import com.google.devtools.ksp.symbol.KSTypeAlias
import com.google.devtools.ksp.symbol.KSValueParameter
import com.google.devtools.ksp.symbol.Modifier
import com.google.devtools.ksp.validate

/** Registers [CommandSymbolProcessor] with KSP. */
public class CommandSymbolProcessorProvider : SymbolProcessorProvider {
    override fun create(environment: SymbolProcessorEnvironment): SymbolProcessor =
        CommandSymbolProcessor(environment.codeGenerator, environment.logger)
}

/** Generates command descriptors for Kotlin sources. Each descriptor depends only on its own source file. */
internal class CommandSymbolProcessor(
    private val codeGenerator: CodeGenerator,
    private val logger: KSPLogger,
) : SymbolProcessor {
    override val apiFeatures: ApiFeatures = buildApiFeatures {
        enableBackingFields = true
        enableContextParameters = true
    }

    override fun process(resolver: Resolver): List<KSAnnotated> {
        val symbols = resolver.getSymbolsWithAnnotation("$ANNOTATIONS_PACKAGE.Command").toList()
        val deferred = symbols.filterNot { it.validate(apiFeatures = apiFeatures) }
        for (symbol in symbols - deferred.toSet()) {
            if (symbol !is KSClassDeclaration || symbol.classKind !in setOf(ClassKind.CLASS, ClassKind.OBJECT)) {
                logger.error("@Command can only be used on classes", symbol)
                continue
            }
            val model = model(symbol)
            val problems = validate(model)
            if (problems.isNotEmpty()) {
                problems.forEach { logger.error(it.message, it.origin as? KSNode ?: symbol) }
                continue
            }
            val file = symbol.containingFile
            val dependencies = if (file == null) Dependencies(false) else Dependencies(false, file)
            codeGenerator.createNewFile(dependencies, model.packageName, model.descriptorName, "java").writer().use {
                it.write(JavaEmitter.emit(model))
            }
        }
        return deferred
    }

    private fun model(type: KSClassDeclaration): CommandModel {
        val command = type.annotation("Command")!!
        val packageName = type.packageName.asString()
        val qualified = type.qualifiedName!!.asString()
        val permission = type.annotation("Permission")?.string("value")
        val methods = type.getAllFunctions()
            .filter { it.annotation("Subcommand") != null || it.annotation("Execute") != null }
            .map(::method)
            .toList()
        val accessible = !type.isPrivate()
        return CommandModel(
            packageName = packageName,
            typeName = qualified,
            flatName = qualified.removePrefix(if (packageName.isEmpty()) "" else "$packageName.").replace('.', '_'),
            name = command.string("name").orEmpty(),
            aliases = command.strings("aliases"),
            description = command.string("description").orEmpty(),
            permission = permission,
            generic = type.typeParameters.isNotEmpty(),
            methods = if (accessible) methods else methods.map { it.withProblem("Command classes must not be private") },
            origin = type,
        )
    }

    private fun method(function: KSFunctionDeclaration): MethodModel {
        val subcommand = function.annotation("Subcommand")
        val execute = function.annotation("Execute")
        val problem = when {
            subcommand != null && execute != null -> "Use either @Subcommand or @Execute, not both"
            Modifier.SUSPEND in function.modifiers -> "Command methods must not be suspend functions"
            function.extensionReceiver != null -> "Command methods must not be extension functions"
            function.typeParameters.isNotEmpty() -> "Command methods must not have type parameters"
            else -> null
        }
        val returnType = function.returnType?.resolve()?.declaration?.qualifiedName?.asString()
        return MethodModel(
            name = function.simpleName.asString(),
            path = subcommand?.string("path")?.trim()?.split(Regex("\\s+"))?.filter(String::isNotEmpty),
            aliases = subcommand?.strings("aliases").orEmpty(),
            description = subcommand?.string("description").orEmpty(),
            permission = function.annotation("Permission")?.string("value"),
            cooldownSeconds = function.annotation("Cooldown")?.argument("seconds") as? Long,
            parameters = function.parameters.map(::parameter),
            returnsVoid = returnType == null || returnType == "kotlin.Unit",
            accessible = function.isPublic(),
            frontEndProblem = problem,
            origin = function,
        )
    }

    private fun parameter(parameter: KSValueParameter): ParameterModel {
        val type = parameter.type.resolve().expandAliases()
        val qualified = type.declaration.qualifiedName?.asString().orEmpty()
        val mapped = kotlinTypes[qualified]
        val kind = when {
            parameter.annotation("Sender") != null -> ParameterKind.SENDER
            qualified == "$COMMANDS_PACKAGE.CommandContext" -> ParameterKind.CONTEXT
            qualified == "$COMMANDS_PACKAGE.CommandSource" -> ParameterKind.SOURCE
            else -> ParameterKind.ARGUMENT
        }
        val unsupported = when {
            parameter.isVararg -> "varargs are not supported"
            qualified.startsWith("kotlin.collections.") || qualified == "kotlin.Array" -> "collections and arrays are not supported"
            type.arguments.isNotEmpty() -> "generic types are not supported"
            qualified.isEmpty() -> "the type could not be resolved"
            else -> null
        }
        val range = parameter.annotation("Range")
        val arg = parameter.annotation("Arg")
        return ParameterModel(
            kind = kind,
            sourceName = parameter.name?.asString().orEmpty(),
            explicitName = arg?.string("name"),
            description = arg?.string("description").orEmpty(),
            typeName = mapped?.first ?: qualified,
            valueKind = mapped?.second ?: ValueKind.OTHER,
            primitive = mapped != null && qualified != "kotlin.String" && !type.isMarkedNullable,
            nullable = type.isMarkedNullable,
            unsupportedReason = unsupported,
            optional = parameter.annotation("Optional") != null,
            greedy = parameter.annotation("Greedy") != null,
            range = range?.let {
                (it.argument("min") as? Double ?: -Double.MAX_VALUE)..(it.argument("max") as? Double ?: Double.MAX_VALUE)
            },
            origin = parameter,
        )
    }

    private fun KSType.expandAliases(): KSType {
        val declaration = declaration
        return if (declaration is KSTypeAlias) {
            declaration.type.resolve().let { if (isMarkedNullable) it.makeNullable() else it }.expandAliases()
        } else {
            this
        }
    }

    private fun KSAnnotated.annotation(simpleName: String): KSAnnotation? = annotations.firstOrNull {
        it.shortName.asString() == simpleName &&
            it.annotationType.resolve().declaration.qualifiedName?.asString() == "$ANNOTATIONS_PACKAGE.$simpleName"
    }

    private fun KSAnnotation.argument(name: String): Any? =
        arguments.firstOrNull { it.name?.asString() == name }?.value
            ?: defaultArguments.firstOrNull { it.name?.asString() == name }?.value

    private fun KSAnnotation.string(name: String): String? = argument(name) as? String

    private fun KSAnnotation.strings(name: String): List<String> = (argument(name) as? List<*>).orEmpty().filterIsInstance<String>()

    private fun MethodModel.withProblem(problem: String) = MethodModel(
        name, path, aliases, description, permission, cooldownSeconds, parameters, returnsVoid, accessible,
        frontEndProblem ?: problem, origin,
    )

    private companion object {
        val kotlinTypes = mapOf(
            "kotlin.String" to ("java.lang.String" to ValueKind.STRING),
            "kotlin.Int" to ("java.lang.Integer" to ValueKind.INT),
            "kotlin.Long" to ("java.lang.Long" to ValueKind.LONG),
            "kotlin.Double" to ("java.lang.Double" to ValueKind.DOUBLE),
            "kotlin.Boolean" to ("java.lang.Boolean" to ValueKind.BOOLEAN),
            "kotlin.Float" to ("java.lang.Float" to ValueKind.OTHER),
            "kotlin.Short" to ("java.lang.Short" to ValueKind.OTHER),
            "kotlin.Byte" to ("java.lang.Byte" to ValueKind.OTHER),
            "kotlin.Char" to ("java.lang.Character" to ValueKind.OTHER),
        )
    }
}
