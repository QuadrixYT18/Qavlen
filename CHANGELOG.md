# Changelog

All notable changes are documented here. The project follows [Semantic Versioning](https://semver.org) once 1.0.0 is released.

## Unreleased

### Added

- `commands`: `CommandCondition` and `Builder.condition` for requirements beyond permissions; `@Require` for annotated commands.
- `commands`: generated help with `CommandDispatcher.help`, `sendHelp` and `CommandContext.sendHelp`, translated per source.
- `commands`: length limits for text arguments with `ArgumentTypes.word(min, max)`, `greedyString(min, max)` and `@Length`; enforced on Paper, Velocity and Discord.
- `commands`: `CommandDispatcher.reportError` and `describe` for platform adapters.
- `config`: `ConfigSchema` with required and optional typed values, ranges, choices and detection of unknown keys; `ConfigOptions.Builder.schema`.
- `config`: change listeners with `ConfigFile.addListener` and `reloadIfModified()` for cheap hot reloading.

### Fixed

- `commands-processor`: `@Permission` on an `@Execute` method was ignored. It now applies, and combinations that would silently restrict subcommands are reported at compile time.
- `commands`: a failing error handler no longer prevents the failure message from being sent.

## 0.3.1 - 2026-11-19

### Added

- `commands`: `Cooldowns.tryStart` starts a cooldown atomically.
- `network`: `RestClient` is `AutoCloseable` and limits response bodies with `Builder.maxResponseSize` (16 MiB by default); larger responses fail with `HttpResponseTooLargeException`.
- `storage`: `Database.Builder.queryTimeout` cancels statements that run too long.
- `commands`: `CommandDispatcher.suggestions` filters argument suggestions by prefix; Paper, Velocity and Discord now share it.

### Fixed

- `cache-redis`: entries that no longer match the value class, for example after an update, are deleted and treated as misses instead of failing every read.
- `storage`: asynchronous calls after `close()` complete exceptionally instead of throwing, and a failure while restoring auto-commit no longer hides the error that rolled back a transaction.
- `commands-processor`: the javac processor now picks up `@Subcommand` and `@Execute` methods inherited from base classes, as the KSP processor already did.
- `commands`, `brigadier`, `jda`: an exception in a custom `ArgumentType.suggest` is logged and yields no suggestions instead of breaking tab completion or Discord autocomplete.
- `jda`: interactions that arrive after `close()` are ignored instead of throwing on JDA's event thread, unexpected exceptions while reading options are reported, and `close()` waits for running executors before stopping the deferral timer.
- `commands`: cooldowns are reserved atomically before a command runs, so concurrent executions on Velocity or Discord can no longer bypass them; failed executions still do not consume the cooldown. Expired cooldowns are purged automatically.
- `core`: `writeAtomically` flushes the file to disk before replacing the target, keeps the target's POSIX permissions and writes through symbolic links instead of replacing them.
- `config`: migrations are saved even when `writeDefaults(false)` is set; previously they ran again on every load.
- `config`: migration backups never overwrite an existing backup from the same second.
- `config`: unquoted YAML dates such as `2025-01-01` stay text; previously they were rewritten as a different timestamp string on save.
- `config`: integers beyond the `Long` range stay numbers instead of turning into strings, and JSON numbers keep their full precision.

## 0.3.0 - 2026-11-19

### Added

- `serialization`: `ConfigBinding` reads configuration sections into Kotlin data classes, records and Java beans and writes them back, with kebab-case keys, compact durations, case-insensitive enums and error messages that name the full path.
- `serialization`: `JsonCodec.Builder.propertyNaming` and `caseInsensitiveEnums`.

### Changed

- `serialization` now depends on `config`.

## 0.2.0 - 2026-11-19

### Added

- `paper`: `ItemBuilder` for item stacks based on the data component API, with translated, non-italic names and lore.
- `paper`: `PluginChannel`, the backend counterpart of the Velocity plugin messaging channel.
- `adventure`: `AdventureMessages.renderLines` renders multi-line translations as one component per line.

### Changed

- `brigadier`: incomplete commands on Paper and Velocity reply with the translated usage line instead of the platform's generic error.
- `commands`: `CommandDispatcher.usage(path)` is public.

## 0.1.0 - 2026-11-19

### Added

- `core`: resource scopes, atomic file writes and compact duration parsing.
- `config`: YAML and JSON files with typed access, non-destructive default merging, validation, versioned migrations with backups, safe reloads and atomic saves.
- `language`: translations from YAML, JSON and properties files with locale fallbacks, namespaces, typed placeholders, plural forms, validation and atomic reloads.
- `commands`: command definitions, typed arguments, permissions, cooldowns, suggestions and localized error messages in English and German.
- `commands-processor`: KSP and javac annotation processing with compile-time validation.
- `adventure`: MiniMessage rendering with literal placeholder values and prefixes.
- `paper`: Brigadier commands through the lifecycle API, menus with pagination, Folia-aware scheduler, event subscriptions, persistent data keys and safe teleports.
- `velocity`: Brigadier commands and plugin messaging channels.
- `jda`: slash commands with subcommand groups, localized descriptions, autocomplete, automatic deferral and change-aware registration.
- `storage`, `cache`, `cache-redis`, `serialization`, `network`, `scheduler`.
- Examples for plain JVM, Paper, Velocity and JDA in Kotlin and Java.
