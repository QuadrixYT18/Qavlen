Qavlen is a set of Kotlin and Java libraries for JVM applications, with integrations for Paper, Velocity and JDA. The general modules work without Minecraft or Discord; the platform modules build on them.

| Module | Artifact | Purpose |
| --- | --- | --- |
| [Core](Core) | `qavlen-core` | Resource scopes, atomic file writes, duration parsing |
| [Configuration](Configuration) | `qavlen-config` | YAML and JSON files with defaults, validation, migrations and safe reloads |
| [Language](Language-and-Localization) | `qavlen-language` | Translations with locale fallbacks, namespaces and typed placeholders |
| [Commands](Commands) | `qavlen-commands` | Command trees, typed arguments, permissions, cooldowns, localized errors |
| [Annotations](Annotations) | `qavlen-commands-processor` | KSP and javac processor for `@Command` classes |
| [Adventure](Adventure) | `qavlen-adventure` | MiniMessage rendering of translations with injection-safe placeholders |
| [Brigadier](Brigadier) | `qavlen-brigadier` | Shared Brigadier tree builder used by Paper and Velocity |
| [Paper](Paper) | `qavlen-paper` | Brigadier commands, menus, items, plugin messaging, Folia-aware scheduling, events, persistent data, safe teleports |
| [Velocity](Velocity) | `qavlen-velocity` | Proxy commands and plugin messaging |
| [JDA](JDA) | `qavlen-jda` | Localized slash commands with autocomplete and change-aware registration |
| [Storage](Storage) | `qavlen-storage` | HikariCP pool, prepared statements, transactions, migrations |
| [Cache](Cache) | `qavlen-cache` | Caffeine-backed local cache |
| [Redis](Redis) | `qavlen-cache-redis` | Shared cache in Redis |
| [Serialization](Serialization) | `qavlen-serialization` | Jackson-based JSON codec |
| [Networking](Networking) | `qavlen-network` | JSON REST client with timeouts and retries |
| [Scheduler](Scheduler) | `qavlen-scheduler` | Delayed and repeating tasks for plain JVM applications |

Start with [Getting Started](Getting-Started). Complete projects are in the [`examples`](https://github.com/QuadrixYT18/Qavlen/tree/master/examples) directory.
