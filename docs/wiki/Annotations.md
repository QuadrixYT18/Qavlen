# Annotations

Annotated command classes are processed at build time. The processor validates them and generates one small Java class per command, `<Class>_QavlenCommand`, which builds the `CommandDefinition` and calls your methods directly. There is no classpath scanning and no reflective method invocation at runtime; `dispatcher.register(instance)` loads the generated class by name.

```kotlin
@Command("coins", aliases = ["money"], description = "coins.description")
@Permission("coins.use")
class CoinsCommand(private val economy: Economy) {
    @Execute
    fun balance(@Sender player: Player) { ... }

    @Subcommand("give")
    @Permission("coins.admin")
    @Cooldown(5)
    fun give(@Sender sender: CommandSender, target: Player, @Range(min = 1.0, max = 1_000_000.0) amount: Int) { ... }

    @Subcommand("admin reset")
    fun reset(context: CommandContext, @Arg("player-name") name: String, @Greedy @Optional reason: String?) { ... }
}
```

| Annotation | Target | Meaning |
| --- | --- | --- |
| `@Command(name, aliases, description)` | class | root command |
| `@Execute` | method | runs when the root command is used without subcommand |
| `@Subcommand(path, aliases, description)` | method | subcommand; `"admin reset"` creates nested nodes |
| `@Permission(value)` | class, method | required permission; on the class it applies to every subcommand |
| `@Cooldown(seconds)` | method | per-source cooldown |
| `@Arg(name, description)` | parameter | argument name and description |
| `@Optional` | parameter | optional argument (nullable in Kotlin, wrapper type in Java) |
| `@Greedy` | parameter | last `String` argument takes the rest of the input |
| `@Range(min, max)` | parameter | bounds for `Int`, `Long` or `Double` |
| `@Sender` | parameter | the command source converted to the parameter type |

Parameters of type `CommandContext` and `CommandSource` are injected. All other parameters are arguments; their type is resolved through the dispatcher's `ArgumentTypeRegistry` when the command is registered. Argument names default to the parameter name in kebab case (`playerName` becomes `player-name`). Descriptions are translation keys or literal text.

## Compile-time checks

The build fails with a message on the offending element when, for example:

- a name or alias is not 1-32 lowercase letters, digits, `-` or `_`;
- a subcommand path is declared twice, or a node has both arguments and subcommands;
- a method is not public, returns a value, is static, is a suspend or extension function, or uses varargs;
- a parameter is generic, an array or a collection;
- a required argument follows an optional one, `@Greedy` is not on the last `String`, `@Range` is not on a number;
- an optional argument is primitive, or a non-optional Kotlin parameter is nullable.

## Build setup

Kotlin uses KSP (`ksp(...)`), Java uses `annotationProcessor(...)`, see [Installation](Installation). Both generate identical descriptors. The KSP processor is isolating per source file and the javac processor is registered as isolating for Gradle's incremental compilation.
