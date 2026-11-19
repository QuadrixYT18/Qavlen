package de.quadrixyt.qavlen.commands

import de.quadrixyt.qavlen.core.Durations
import java.time.Duration
import java.util.Locale
import java.util.UUID

/**
 * Converts user input into a typed value.
 *
 * Platform adapters map the built-in types in [ArgumentTypes] to native argument types (Brigadier, Discord
 * option types). Any other type is offered to the platform as a word, or the rest of the input if [greedy], and
 * parsed with [parse].
 */
public interface ArgumentType<T : Any> {
    public val valueType: Class<T>

    /** Consumes the remaining input, including spaces. Only allowed for the last argument. */
    public val greedy: Boolean get() = false

    /** @throws CommandException with a translated reason when [input] is invalid */
    public fun parse(input: String, source: CommandSource): T

    /** Suggestions for the partially typed [input]. Results are filtered by prefix by the caller. */
    public fun suggest(input: String, source: CommandSource): List<String> = emptyList()
}

/** A single word or, when [greedy], the remaining input. */
public class StringArgument internal constructor(override val greedy: Boolean) : ArgumentType<String> {
    override val valueType: Class<String> get() = String::class.java

    override fun parse(input: String, source: CommandSource): String = input
}

/** A whole number within [min]..[max]. */
public class IntegerArgument internal constructor(public val min: Int, public val max: Int) : ArgumentType<Int> {
    override val valueType: Class<Int> get() = Int::class.javaObjectType

    override fun parse(input: String, source: CommandSource): Int {
        val value = input.toIntOrNull() ?: throw CommandException(CommandException.INVALID_NUMBER, mapOf("input" to input))
        checkRange(value.toDouble(), min, max)
        return value
    }
}

/** A whole number within [min]..[max]. */
public class LongArgument internal constructor(public val min: Long, public val max: Long) : ArgumentType<Long> {
    override val valueType: Class<Long> get() = Long::class.javaObjectType

    override fun parse(input: String, source: CommandSource): Long {
        val value = input.toLongOrNull() ?: throw CommandException(CommandException.INVALID_NUMBER, mapOf("input" to input))
        if (value !in min..max) throw CommandException(CommandException.NUMBER_RANGE, mapOf("min" to min, "max" to max))
        return value
    }
}

/** A decimal number within [min]..[max]. */
public class DoubleArgument internal constructor(public val min: Double, public val max: Double) : ArgumentType<Double> {
    override val valueType: Class<Double> get() = Double::class.javaObjectType

    override fun parse(input: String, source: CommandSource): Double {
        val value = input.toDoubleOrNull()?.takeIf { it.isFinite() }
            ?: throw CommandException(CommandException.INVALID_NUMBER, mapOf("input" to input))
        checkRange(value, min, max)
        return value
    }
}

/** `true` or `false`. */
public object BooleanArgument : ArgumentType<Boolean> {
    override val valueType: Class<Boolean> get() = Boolean::class.javaObjectType

    override fun parse(input: String, source: CommandSource): Boolean = when (input.lowercase(Locale.ROOT)) {
        "true" -> true
        "false" -> false
        else -> throw CommandException(CommandException.INVALID_BOOLEAN, mapOf("input" to input))
    }

    override fun suggest(input: String, source: CommandSource): List<String> = listOf("true", "false")
}

/** One constant of [valueType], matched case-insensitively by name. */
public class EnumArgument<E : Enum<E>> internal constructor(override val valueType: Class<E>) : ArgumentType<E> {
    private val constants = valueType.enumConstants.associateBy { it.name.lowercase(Locale.ROOT) }

    /** The accepted inputs in declaration order. */
    public val choices: List<String> = constants.keys.toList()

    override fun parse(input: String, source: CommandSource): E = constants[input.lowercase(Locale.ROOT)]
        ?: throw CommandException(CommandException.INVALID_CHOICE, mapOf("input" to input, "choices" to choices.joinToString(", ")))

    override fun suggest(input: String, source: CommandSource): List<String> = choices
}

private fun checkRange(value: Double, min: Number, max: Number) {
    if (value < min.toDouble() || value > max.toDouble()) {
        throw CommandException(CommandException.NUMBER_RANGE, mapOf("min" to min, "max" to max))
    }
}

/** Factories for the built-in argument types. */
public object ArgumentTypes {
    private val word = StringArgument(false)
    private val greedyString = StringArgument(true)

    @JvmStatic
    public fun word(): ArgumentType<String> = word

    @JvmStatic
    public fun greedyString(): ArgumentType<String> = greedyString

    @JvmStatic
    @JvmOverloads
    public fun integer(min: Int = Int.MIN_VALUE, max: Int = Int.MAX_VALUE): ArgumentType<Int> {
        require(min <= max) { "min must not exceed max" }
        return IntegerArgument(min, max)
    }

    @JvmStatic
    @JvmOverloads
    public fun longNumber(min: Long = Long.MIN_VALUE, max: Long = Long.MAX_VALUE): ArgumentType<Long> {
        require(min <= max) { "min must not exceed max" }
        return LongArgument(min, max)
    }

    @JvmStatic
    @JvmOverloads
    public fun decimal(min: Double = -Double.MAX_VALUE, max: Double = Double.MAX_VALUE): ArgumentType<Double> {
        require(min <= max) { "min must not exceed max" }
        return DoubleArgument(min, max)
    }

    @JvmStatic
    public fun bool(): ArgumentType<Boolean> = BooleanArgument

    @JvmStatic
    public fun <E : Enum<E>> enumeration(type: Class<E>): ArgumentType<E> = EnumArgument(type)

    @JvmStatic
    public fun uuid(): ArgumentType<UUID> = UuidArgument

    @JvmStatic
    public fun duration(): ArgumentType<Duration> = DurationArgument

    private object UuidArgument : ArgumentType<UUID> {
        override val valueType: Class<UUID> get() = UUID::class.java

        override fun parse(input: String, source: CommandSource): UUID = try {
            UUID.fromString(input)
        } catch (_: IllegalArgumentException) {
            throw CommandException(CommandException.INVALID_UUID, mapOf("input" to input))
        }
    }

    private object DurationArgument : ArgumentType<Duration> {
        override val valueType: Class<Duration> get() = Duration::class.java

        override fun parse(input: String, source: CommandSource): Duration = try {
            Durations.parse(input)
        } catch (_: RuntimeException) {
            throw CommandException(CommandException.INVALID_DURATION, mapOf("input" to input))
        }
    }
}
