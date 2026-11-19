`qavlen-brigadier` converts [command definitions](Commands) into [Brigadier](https://github.com/Mojang/brigadier) trees. [Paper](Paper) and [Velocity](Velocity) use it internally, so most projects never touch it directly. It is useful for other Brigadier-based platforms or to register nodes manually.

The module compiles against `com.mojang:brigadier` but does not bundle it. Paper ships Mojang's Brigadier and Velocity ships an API-compatible fork; both are tested.

## How definitions map to Brigadier

- Every command, subcommand and alias becomes a literal node. Aliases of subcommands are separate literals with the same children.
- Arguments become a chain of argument nodes. An `executes` handler is attached wherever the remaining arguments are optional, so `/coins give 5` and `/coins give 5 reason` both work.
- Permission checks are Brigadier requirements: clients do not see commands they cannot use.
- Integer, long, decimal, boolean and string arguments become native Brigadier types with their ranges. Other types are sent as strings, offer suggestions from `ArgumentType.suggest` and are parsed with `ArgumentType.parse` during execution.
- Execution calls `CommandDispatcher.invoke`, so permissions, cooldowns, translated errors and error handling are the same as on every other platform.

## Using it on another platform

Implement `BrigadierPlatform<S>` for the platform's source type:

```kotlin
class MyPlatform : BrigadierPlatform<MySource> {
    override fun source(source: MySource): CommandSource = MyCommandSource(source)

    // optional: native argument types for platform-specific Qavlen types
    override fun nativeType(type: ArgumentType<*>) = if (type is LocationArgument) MyArgumentTypes.location() else null

    // optional: convert the native value back to the Qavlen value
    override fun convert(context: CommandContext<MySource>, argument: CommandArgument<*>): Any? =
        if (argument.type is LocationArgument) context.getArgument(argument.name, Location::class.java) else null
}

val tree = BrigadierTree(dispatcher, MyPlatform())
brigadierDispatcher.root.addChild(tree.build(definition))
```

`convert` may throw `CommandException` when a native value does not resolve, for example a selector that matches nobody; the message is translated for the source.
