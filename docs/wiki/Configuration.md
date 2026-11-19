# Configuration

`qavlen-config` reads and writes YAML (SnakeYAML) and JSON (Gson) documents.

```kotlin
val config = YamlConfig.load(
    Path.of("config.yml"),
    ConfigOptions {
        defaults(mapOf("database" to mapOf("host" to "localhost", "port" to 3306)))
        validator(ConfigValidator.range("database.port", 1, 65535))
    },
)

val port = config.getInt("database.port", 3306)
config.set("database.port", 3307)
config.save()
```

```java
YamlConfig config = YamlConfig.load(Path.of("config.yml"), ConfigOptions.builder()
    .defaults(Map.of("database", Map.of("host", "localhost", "port", 3306)))
    .build());
int port = config.getInt("database.port", 3306);
```

## Reading values

Paths are dot-separated. Typed getters (`getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getList`, `getStringList`, `getIntList`, `getMap`) return `null` when the value is absent, or the given default. A value of the wrong type throws `ConfigValueException` naming the path, so a typo such as `port: 33o6` is reported instead of silently replaced. `getSection` returns a live view of a nested section.

## Defaults

Defaults only add missing keys; existing values are never replaced. Missing keys are written back to the file. If the file has a value where the defaults have a section, or the other way round, loading fails with a `ConfigException` that names the key instead of discarding data. Bundled defaults can come from a resource: `defaults(YamlConfig.read(javaClass.getResourceAsStream("/config.yml")!!))`.

## Safety

- A file that cannot be parsed is never overwritten.
- Loading and `reload()` parse, migrate, merge and validate a copy first. If any step fails, a `ConfigException` is thrown and the previous values stay in place.
- `save()` validates, writes atomically and skips the write when the content did not change. `isDirty` tells whether there are unsaved changes.
- Duplicate keys are rejected in YAML and JSON. JSON parsing is strict (no comments or trailing commas). YAML alias expansion is limited.
- **Comments are not preserved.** SnakeYAML does not keep them, so a file rewritten by Qavlen loses them. Files are only rewritten when defaults or migrations changed them or when you call `save()` after changes.

## Validation

`ConfigValidator` returns a list of `ConfigViolation(path, message)`. All violations are reported together in a `ConfigValidationException`. `ConfigValidator.required(...)` and `ConfigValidator.range(...)` cover common cases.

## Migrations

```kotlin
ConfigOptions {
    version(3)
    migration(1) { config ->
        config.set("database.host", config.getString("db-host"))
        config.remove("db-host")
    }
    migration(2) { config -> config.set("database.pool-size", 4) }
}
```

The version is stored under `config-version` (configurable); a file without it is version 1. Migrations run in order on a copy. Before the migrated file is written, the original is copied to `config.yml.v<old>-<timestamp>.bak` (disable with `backups(false)`). Files with a newer version than the code supports are rejected.

## Streams

`YamlConfig.read(InputStream)`, `YamlConfig.parse(String)` and the JSON equivalents create documents without a backing file. Use `saveTo(path)` or `writeTo(writer)` to write them.
