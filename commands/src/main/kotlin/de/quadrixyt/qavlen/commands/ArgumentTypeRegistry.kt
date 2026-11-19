package de.quadrixyt.qavlen.commands

import java.util.concurrent.ConcurrentHashMap

/**
 * Maps parameter types of annotated commands to [ArgumentType]s. Built-in types and all enums are known;
 * platform adapters add their own, such as players or Discord members.
 */
public class ArgumentTypeRegistry {
    private val types = ConcurrentHashMap<Class<*>, ArgumentType<*>>()

    init {
        register(ArgumentTypes.word())
        register(ArgumentTypes.integer())
        register(ArgumentTypes.longNumber())
        register(ArgumentTypes.decimal())
        register(ArgumentTypes.bool())
        register(ArgumentTypes.uuid())
        register(ArgumentTypes.duration())
    }

    /** Registers [type] for its [ArgumentType.valueType], replacing a previous registration. */
    public fun <T : Any> register(type: ArgumentType<T>): ArgumentTypeRegistry = apply { types[type.valueType] = type }

    /**
     * Returns the argument type for [type]. Primitive classes are treated like their wrappers.
     *
     * @throws IllegalArgumentException when no type is registered
     */
    @Suppress("UNCHECKED_CAST")
    public fun <T : Any> resolve(type: Class<T>): ArgumentType<T> {
        val boxed = type.kotlin.javaObjectType
        types[boxed]?.let { return it as ArgumentType<T> }
        if (boxed.isEnum) return EnumArgument(boxed as Class<AnyEnum>) as ArgumentType<T>
        throw IllegalArgumentException(
            "No argument type registered for ${type.name}. Register one with ArgumentTypeRegistry.register(...) " +
                "before registering the command.",
        )
    }

    public fun contains(type: Class<*>): Boolean = types.containsKey(type.kotlin.javaObjectType) || type.isEnum

    /** Stands in for the unknown enum type parameter; erased at runtime. */
    private enum class AnyEnum
}
