package de.quadrixyt.qavlen.commands.annotations

/**
 * Declares a root command. The annotation processor generates a `<Class>_QavlenCommand` descriptor, which
 * `CommandDispatcher.register(instance)` loads.
 *
 * @property name lowercase command name
 * @property description plain text or a translation key
 */
@Target(AnnotationTarget.CLASS)
@Retention(AnnotationRetention.BINARY)
public annotation class Command(
    val name: String,
    val aliases: Array<String> = [],
    val description: String = "",
)

/**
 * Marks a method as a subcommand. Nested paths are separated by spaces: `@Subcommand("admin reset")`.
 * Intermediate nodes are created automatically.
 */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Subcommand(val path: String, val aliases: Array<String> = [], val description: String = "")

/** Marks the method that runs when the root command is used without a subcommand. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Execute

/** Requires a permission. On a class it applies to the root command and therefore to every subcommand. */
@Target(AnnotationTarget.CLASS, AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Permission(val value: String)

/** Minimum number of seconds between two executions by the same source. */
@Target(AnnotationTarget.FUNCTION)
@Retention(AnnotationRetention.BINARY)
public annotation class Cooldown(val seconds: Long)

/**
 * Customizes an argument. Without it the parameter name is used, which requires parameter names to be
 * available at compile time (always the case for Kotlin and for Java sources processed by javac).
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Arg(val name: String = "", val description: String = "")

/** Marks an argument as optional. Kotlin parameters must be nullable, Java parameters must not be primitive. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Optional

/** Lets the last `String` argument consume the rest of the input, including spaces. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Greedy

/** Restricts an `Int`, `Long` or `Double` argument to an inclusive range. */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Range(val min: Double = -Double.MAX_VALUE, val max: Double = Double.MAX_VALUE)

/**
 * Injects the command source converted to the parameter type, for example a Paper `Player`. Execution is
 * rejected with a translated message when the source is of another kind.
 */
@Target(AnnotationTarget.VALUE_PARAMETER)
@Retention(AnnotationRetention.BINARY)
public annotation class Sender
