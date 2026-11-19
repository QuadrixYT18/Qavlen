# Changelog

All notable changes are documented here. The project follows [Semantic Versioning](https://semver.org) once 1.0.0 is released.

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
