package de.quadrixyt.qavlen.commands.processor

import java.io.IOException
import javax.annotation.processing.AbstractProcessor
import javax.annotation.processing.RoundEnvironment
import javax.lang.model.SourceVersion
import javax.lang.model.element.AnnotationMirror
import javax.lang.model.element.Element
import javax.lang.model.element.ElementKind
import javax.lang.model.element.ExecutableElement
import javax.lang.model.element.Modifier
import javax.lang.model.element.NestingKind
import javax.lang.model.element.TypeElement
import javax.lang.model.element.VariableElement
import javax.lang.model.type.ArrayType
import javax.lang.model.type.DeclaredType
import javax.lang.model.type.TypeKind
import javax.lang.model.type.TypeMirror
import javax.lang.model.util.ElementFilter
import javax.tools.Diagnostic

/** Generates command descriptors for Java sources (JSR 269). Registered as an isolating processor for Gradle. */
public class CommandAnnotationProcessor : AbstractProcessor() {
    override fun getSupportedAnnotationTypes(): Set<String> = setOf("$ANNOTATIONS_PACKAGE.Command")

    override fun getSupportedSourceVersion(): SourceVersion = SourceVersion.latestSupported()

    override fun process(annotations: Set<TypeElement>, environment: RoundEnvironment): Boolean {
        val command = processingEnv.elementUtils.getTypeElement("$ANNOTATIONS_PACKAGE.Command") ?: return false
        for (element in environment.getElementsAnnotatedWith(command)) {
            if (element !is TypeElement || element.kind != ElementKind.CLASS) {
                error("@Command can only be used on classes", element)
                continue
            }
            val model = model(element)
            val problems = validate(model)
            if (problems.isNotEmpty()) {
                problems.forEach { error(it.message, it.origin as? Element ?: element) }
                continue
            }
            val name = (if (model.packageName.isEmpty()) "" else "${model.packageName}.") + model.descriptorName
            try {
                processingEnv.filer.createSourceFile(name, element).openWriter().use { it.write(JavaEmitter.emit(model)) }
            } catch (exception: IOException) {
                error("Could not write $name: ${exception.message}", element)
            }
        }
        return true
    }

    private fun model(type: TypeElement): CommandModel {
        val packageName = processingEnv.elementUtils.getPackageOf(type).qualifiedName.toString()
        val command = type.annotation("Command")!!
        // Inherited methods count too, matching the KSP processor, so shared subcommands can live in a base class.
        val methods = ElementFilter.methodsIn(processingEnv.elementUtils.getAllMembers(type))
            .filter { it.annotation("Subcommand") != null || it.annotation("Execute") != null }
            .map(::method)
        val problem = when {
            Modifier.PRIVATE in type.modifiers -> "Command classes must not be private"
            type.nestingKind == NestingKind.MEMBER && Modifier.STATIC !in type.modifiers -> "Nested command classes must be static"
            else -> null
        }
        val qualified = type.qualifiedName.toString()
        return CommandModel(
            packageName = packageName,
            typeName = qualified,
            flatName = qualified.removePrefix(if (packageName.isEmpty()) "" else "$packageName.").replace('.', '_'),
            name = command.string("name").orEmpty(),
            aliases = command.strings("aliases"),
            description = command.string("description").orEmpty(),
            permission = type.annotation("Permission")?.string("value"),
            generic = type.typeParameters.isNotEmpty(),
            methods = if (problem == null) methods else methods.map { it.withProblem(problem) },
            origin = type,
            conditions = conditions(type),
        )
    }

    private fun method(method: ExecutableElement): MethodModel {
        val subcommand = method.annotation("Subcommand")
        val execute = method.annotation("Execute")
        val problem = when {
            subcommand != null && execute != null -> "Use either @Subcommand or @Execute, not both"
            Modifier.STATIC in method.modifiers -> "Command methods must not be static"
            method.isVarArgs -> "Command methods must not use varargs"
            method.typeParameters.isNotEmpty() -> "Command methods must not have type parameters"
            else -> null
        }
        return MethodModel(
            name = method.simpleName.toString(),
            path = subcommand?.string("path")?.trim()?.split(Regex("\\s+"))?.filter(String::isNotEmpty),
            aliases = subcommand?.strings("aliases").orEmpty(),
            description = subcommand?.string("description").orEmpty(),
            permission = method.annotation("Permission")?.string("value"),
            cooldownSeconds = method.annotation("Cooldown")?.value("seconds") as? Long,
            parameters = method.parameters.map(::parameter),
            returnsVoid = method.returnType.kind == TypeKind.VOID,
            accessible = Modifier.PUBLIC in method.modifiers,
            frontEndProblem = problem,
            origin = method,
            conditions = conditions(method),
        )
    }

    private fun parameter(parameter: VariableElement): ParameterModel {
        val type = parameter.asType()
        val (typeName, primitive) = javaType(type)
        val kind = when {
            parameter.annotation("Sender") != null -> ParameterKind.SENDER
            typeName == "$COMMANDS_PACKAGE.CommandContext" -> ParameterKind.CONTEXT
            typeName == "$COMMANDS_PACKAGE.CommandSource" -> ParameterKind.SOURCE
            else -> ParameterKind.ARGUMENT
        }
        val unsupported = when {
            type is ArrayType -> "arrays are not supported"
            type is DeclaredType && type.typeArguments.isNotEmpty() -> "generic types are not supported"
            type.kind == TypeKind.ERROR -> "the type could not be resolved"
            type.kind == TypeKind.TYPEVAR -> "type variables are not supported"
            else -> null
        }
        val range = parameter.annotation("Range")
        val arg = parameter.annotation("Arg")
        return ParameterModel(
            kind = kind,
            sourceName = parameter.simpleName.toString(),
            explicitName = arg?.string("name"),
            description = arg?.string("description").orEmpty(),
            typeName = typeName,
            valueKind = valueKinds[typeName] ?: ValueKind.OTHER,
            primitive = primitive,
            nullable = false,
            unsupportedReason = unsupported,
            optional = parameter.annotation("Optional") != null,
            greedy = parameter.annotation("Greedy") != null,
            range = range?.let { (it.value("min") as? Double ?: -Double.MAX_VALUE)..(it.value("max") as? Double ?: Double.MAX_VALUE) },
            origin = parameter,
            length = parameter.annotation("Length")?.let { (it.value("min") as? Int ?: 0)..(it.value("max") as? Int ?: Int.MAX_VALUE) },
        )
    }

    private fun conditions(element: Element): List<ConditionModel> {
        val values = (element.annotation("Require")?.value("value") as? List<*>).orEmpty()
        val conditionType = processingEnv.elementUtils.getTypeElement("$COMMANDS_PACKAGE.CommandCondition")?.asType()
        return values.mapNotNull { (it as? javax.lang.model.element.AnnotationValue)?.value as? DeclaredType }.map { type ->
            val declaration = type.asElement() as TypeElement
            val name = declaration.qualifiedName.toString()
            val publicNoArgs = ElementFilter.constructorsIn(declaration.enclosedElements)
                .any { Modifier.PUBLIC in it.modifiers && it.parameters.isEmpty() }
            // Kotlin objects compile to a class with a static INSTANCE field and a private constructor.
            val singleton = !publicNoArgs &&
                ElementFilter.fieldsIn(declaration.enclosedElements)
                    .any { it.simpleName.contentEquals("INSTANCE") && Modifier.STATIC in it.modifiers && Modifier.PUBLIC in it.modifiers }
            val problem = when {
                conditionType == null ||
                    !processingEnv.typeUtils.isAssignable(
                        type,
                        conditionType,
                    ) -> "$name does not implement CommandCondition"
                Modifier.ABSTRACT in declaration.modifiers -> "$name must not be abstract"
                declaration.nestingKind == NestingKind.MEMBER && Modifier.STATIC !in declaration.modifiers -> "$name must be static"
                !publicNoArgs && !singleton -> "$name needs a public constructor without parameters"
                else -> null
            }
            ConditionModel(name, singleton, problem)
        }
    }

    /** Qualified name of the boxed type and whether the declared type is primitive. */
    private fun javaType(type: TypeMirror): Pair<String, Boolean> = when {
        type.kind.isPrimitive -> processingEnv.typeUtils.boxedClass(processingEnv.typeUtils.getPrimitiveType(type.kind))
            .qualifiedName.toString() to true
        type is DeclaredType -> (type.asElement() as TypeElement).qualifiedName.toString() to false
        else -> type.toString() to false
    }

    private fun Element.annotation(simpleName: String): AnnotationMirror? = annotationMirrors.firstOrNull {
        (it.annotationType.asElement() as TypeElement).qualifiedName.contentEquals("$ANNOTATIONS_PACKAGE.$simpleName")
    }

    private fun AnnotationMirror.value(name: String): Any? =
        processingEnv.elementUtils.getElementValuesWithDefaults(this).entries
            .firstOrNull { it.key.simpleName.contentEquals(name) }?.value?.value

    private fun AnnotationMirror.string(name: String): String? = value(name) as? String

    private fun AnnotationMirror.strings(name: String): List<String> =
        (value(name) as? List<*>).orEmpty().mapNotNull { (it as? javax.lang.model.element.AnnotationValue)?.value as? String }

    private fun MethodModel.withProblem(problem: String) = MethodModel(
        name, path, aliases, description, permission, cooldownSeconds, parameters, returnsVoid, accessible,
        frontEndProblem ?: problem, origin, conditions,
    )

    private fun error(message: String, element: Element) {
        processingEnv.messager.printMessage(Diagnostic.Kind.ERROR, message, element)
    }

    private companion object {
        val valueKinds = mapOf(
            "java.lang.String" to ValueKind.STRING,
            "java.lang.Integer" to ValueKind.INT,
            "java.lang.Long" to ValueKind.LONG,
            "java.lang.Double" to ValueKind.DOUBLE,
            "java.lang.Boolean" to ValueKind.BOOLEAN,
        )
    }
}
