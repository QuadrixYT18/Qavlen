# Qavlen

Qavlen is a Kotlin and Java library for reusable JVM infrastructure, with optional integrations for Paper, Velocity, and JDA.

The project is being built as small, independently usable modules. Current modules are `core` and `config`; platform integrations are added only when their public APIs are implemented.

## Requirements

- JDK 25
- Gradle 9.9 (use the Gradle Wrapper)

## Modules

| Module | Purpose |
| --- | --- |
| `core` | Small lifecycle and resource ownership primitives |
| `config` | YAML and JSON documents, typed access, defaults, validation, reload, and atomic saves |

## Configuration

```kotlin
val config = ConfigDocument.yaml(
    Path.of("config.yml"),
    mapOf("database" to mapOf("host" to "localhost", "port" to 3306)),
)

val port = config.getInt("database.port", 3306)
config.set("database.port", 3307)
config.save()
```

```java
ConfigDocument config = ConfigDocument.yaml(
    Path.of("config.yml"),
    Map.of("database", Map.of("host", "localhost", "port", 3306))
);

int port = config.getInt("database.port", 3306);
config.set("database.port", 3307);
config.save();
```

The loader rejects duplicate YAML keys and non-object document roots. Reload parses and validates a candidate before replacing the current snapshot. Saves write to a temporary file and use atomic replacement when the filesystem supports it. YAML comments are not preserved by SnakeYAML.

## Build

```shell
./gradlew clean build
```

## Publishing

Modules use Gradle's `maven-publish` model and are intended for JitPack. Published coordinates will be documented after a tagged build has been validated.
