`qavlen-commands` defines commands once and runs them on every platform. The platform adapters translate the same `CommandDefinition` into Brigadier trees ([Paper](Paper), [Velocity](Velocity)) or slash commands ([JDA](JDA)); the `CommandDispatcher` parses plain text for console applications and tests.

## Defining commands

Annotations are the most convenient way, see [Annotations](Annotations). The same structure can be built in code:

```kotlin
val coins = command("coins") { root ->
    root.aliases("money").description("coins.description")
    root.subcommand("give") { give ->
        give.permission("coins.admin")
            .cooldown(Duration.ofSeconds(5))
            .argument("target", ArgumentTypes.word())
            .argument("amount", ArgumentTypes.integer(1, 1000))
            .optionalArgument("reason", ArgumentTypes.greedyString())
            .executes { context ->
                val amount = context.argument<Int>("amount")
                context.replyTranslated("coins.given", placeholder("amount", amount))
            }
    }
}
dispatcher.register(coins)
```

```java
CommandDefinition coins = CommandDefinition.builder("coins")
    .subcommand("give", give -> give
        .permission("coins.admin")
        .argument("amount", ArgumentTypes.integer(1, 1000))
        .executes(context -> context.reply("Gave " + context.argument("amount", Integer.class))))
    .build();
```

A node has either subcommands or arguments. Optional arguments follow required ones, and only the last argument may be greedy. Invalid structures fail when the definition is built.

## Argument types

| Type | Factory | Native mapping |
| --- | --- | --- |
| `String` | `word()`, `greedyString()` | Brigadier string / Discord string |
| `Int`, `Long`, `Double` | `integer(min, max)`, `longNumber(...)`, `decimal(...)` | Brigadier numbers / Discord integer and number with range |
| `Boolean` | `bool()` | Brigadier bool / Discord boolean |
| enums | `enumeration(Class)` | suggestions / Discord choices |
| `UUID`, `Duration` | `uuid()`, `duration()` | string with validation |
| `Player`, `World` | registered by `PaperCommands` | Paper selector and world arguments |
| `Player`, `RegisteredServer` | registered by `VelocityCommands` | string with suggestions |
| `User`, `Member`, `Role`, `GuildChannel`, `Message.Attachment` | registered by `JdaCommands` | Discord entity options |

Custom types implement `ArgumentType<T>` (`parse` and optional `suggest`) and are registered with `dispatcher.argumentTypes.register(...)`, which also makes them available to annotated parameters.

## Execution

Every execution goes through `CommandDispatcher.invoke`, which checks the permission of every node on the path, the cooldown and then runs the executor:

- `CommandException(key, placeholders)` ends execution with a translated message. Use it for expected failures.
- Any other exception is passed to `dispatcher.errorHandler` (logged by default) and answered with `qavlen:command.failed`.
- Cooldowns are per `CommandSource.id` and command path. Sources with `cooldownBypassPermission` skip them.

## Messages

Built-in messages use the `qavlen` namespace (`qavlen:command.no-permission`, `qavlen:command.usage`, ...). English and German are bundled. Define the same keys in your translations to override them; keys you do not define fall back to the bundled text. `context.replyTranslated(key, ...)` sends your own translations in the source's locale.

## Sources

`CommandSource` describes who executes a command: `name`, `id`, `locale`, `hasPermission`, `sendMessage`, and the native `handle`. `context.sender(Player::class.java)` converts the source and fails with a translated message when it has another type, for example when the console runs a player-only command.
