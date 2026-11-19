# Qavlen

Qavlen is a modular Kotlin and Java library for JVM applications with integrations for [Paper](https://papermc.io), [Velocity](https://papermc.io/software/velocity) and [JDA](https://github.com/discord-jda/JDA). Its general modules — configuration, localization, commands, storage, caching, serialization, networking and scheduling — work without Minecraft or Discord; the platform modules build on them.

Commands are defined once, with annotations or in code, and run as native Brigadier commands on Paper and Velocity, as slash commands on Discord, or from plain text in any application.

```kotlin
@Command("coins", description = "coins.description")
class CoinsCommand(private val economy: Economy) {
    @Execute
    fun balance(@Sender player: Player, context: CommandContext) {
        context.replyTranslated("coins.balance", placeholder("amount", economy.balance(player)))
    }

    @Subcommand("give")
    @Permission("coins.admin")
    fun give(target: Player, @Range(min = 1.0) amount: Int) {
        economy.deposit(target, amount)
    }
}
```

```java
@Command(name = "coins")
public final class CoinsCommand {
    @Subcommand(path = "give")
    @Permission("coins.admin")
    public void give(CommandContext context, Player target, @Range(min = 1) int amount) {
        context.replyTranslated("coins.given", Placeholder.of("amount", amount));
    }
}
```

The annotation processor (KSP for Kotlin, javac for Java) validates command classes at compile time and generates small descriptors, so there is no classpath scanning or reflective invocation at runtime.

## Modules

| Artifact | Purpose |
| --- | --- |
| `qavlen-core` | Resource scopes, atomic file writes, duration parsing |
| `qavlen-config` | YAML/JSON files with defaults, validation, migrations, backups and safe reloads |
| `qavlen-language` | Translations with locale fallbacks, namespaces and typed placeholders |
| `qavlen-commands` | Command definitions, typed arguments, permissions, cooldowns, localized errors |
| `qavlen-commands-processor` | Annotation processor for `ksp` and `annotationProcessor` |
| `qavlen-adventure` | MiniMessage rendering with injection-safe placeholders |
| `qavlen-brigadier` | Brigadier tree builder shared by Paper and Velocity |
| `qavlen-paper` | Brigadier commands, menus, items, plugin messaging, Folia-aware scheduling, events, persistent data, safe teleports |
| `qavlen-velocity` | Proxy commands and plugin messaging |
| `qavlen-jda` | Localized slash commands, autocomplete, change-aware registration |
| `qavlen-storage` | HikariCP, prepared statements, transactions, async queries, migrations |
| `qavlen-cache` / `qavlen-cache-redis` | Caffeine local cache and optional Redis cache |
| `qavlen-serialization` | Jackson-based JSON codec |
| `qavlen-network` | JSON REST client with timeouts and bounded retries |
| `qavlen-scheduler` | Error-tolerant delayed and repeating tasks |

## Installation

Qavlen requires Java 25 and is published through [JitPack](https://jitpack.io/#QuadrixYT18/Qavlen). Replace `VERSION` with a release tag such as `v0.1.0` or a commit hash.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-paper:VERSION")
    ksp("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION")              // Kotlin
    // annotationProcessor("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION") // Java
}
```

Platform APIs (Paper, Velocity, JDA, JDBC drivers) are not bundled; declare them yourself. Plugins should shade and relocate Qavlen — see [Installation](docs/wiki/Installation.md).

## Documentation

- [Wiki](https://github.com/QuadrixYT18/Qavlen/wiki), maintained in [`docs/wiki`](docs/wiki)
- [Examples](examples) for plain JVM, Paper, Velocity and JDA in Kotlin and Java
- KDoc in the sources; every module publishes a sources jar

## Building

```shell
./gradlew build
```

This compiles all modules and examples and runs the tests, ktlint and detekt. Redis tests run when `REDIS_URL` is set.

## License

Apache License 2.0, see [LICENSE](LICENSE).
