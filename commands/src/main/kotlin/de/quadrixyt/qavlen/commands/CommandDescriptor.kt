package de.quadrixyt.qavlen.commands

/**
 * Generated at build time for every class annotated with [de.quadrixyt.qavlen.commands.annotations.Command].
 * The descriptor builds a [CommandDefinition] that calls the annotated methods directly, without reflection.
 *
 * For a class `com.example.CoinsCommand` the generated class is `com.example.CoinsCommand_QavlenCommand`;
 * nested classes are joined with `_`.
 */
public interface CommandDescriptor<T : Any> {
    public val commandType: Class<T>

    public fun create(instance: T, types: ArgumentTypeRegistry): CommandDefinition

    public companion object {
        public const val SUFFIX: String = "_QavlenCommand"

        /** Name of the descriptor generated for [type]. */
        @JvmStatic
        public fun descriptorName(type: Class<*>): String {
            val packageName = type.packageName
            val simpleNames = type.name.removePrefix(if (packageName.isEmpty()) "" else "$packageName.").replace('$', '_')
            return (if (packageName.isEmpty()) "" else "$packageName.") + simpleNames + SUFFIX
        }

        /**
         * Loads the generated descriptor of [type]. This is a single class lookup by name; nothing is scanned.
         *
         * @throws IllegalArgumentException when no descriptor was generated, usually because the annotation
         * processor is not configured
         */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        public fun <T : Any> forType(type: Class<T>): CommandDescriptor<T> {
            val name = descriptorName(type)
            val descriptorClass = try {
                Class.forName(name, true, type.classLoader)
            } catch (exception: ClassNotFoundException) {
                throw IllegalArgumentException(
                    "No generated command descriptor $name. Add qavlen-commands-processor with ksp(...) for Kotlin " +
                        "or annotationProcessor(...) for Java.",
                    exception,
                )
            }
            val descriptor = descriptorClass.getDeclaredConstructor().newInstance() as CommandDescriptor<T>
            require(descriptor.commandType == type) { "$name describes ${descriptor.commandType.name}, not ${type.name}" }
            return descriptor
        }

        /** Builds the definition of an annotated command instance. */
        @JvmStatic
        @Suppress("UNCHECKED_CAST")
        public fun <T : Any> definitionOf(instance: T, types: ArgumentTypeRegistry): CommandDefinition =
            forType(instance.javaClass).create(instance, types)
    }
}
