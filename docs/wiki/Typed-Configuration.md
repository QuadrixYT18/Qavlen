Typed configurations bind a whole file to a class: a Kotlin data class with default values, a Java record or a Java bean. They live in `qavlen-serialization`, so plugins that only need the path-based [Configuration](Configuration) API do not pull in Jackson.

```kotlin
@Configuration("Example plugin configuration")
data class PluginConfig(
    @Comment("Maximum player count")
    @Range(min = 1.0, max = 100.0)
    val maxPlayers: Int = 20,

    @Comment("Database connection")
    val database: DatabaseSettings = DatabaseSettings(database = "example"),

    val messages: MessagesConfig = MessagesConfig(),
)

data class MessagesConfig(val welcome: String = "<green>Welcome, {player}!")

val config = Configs.open<PluginConfig>(dataPath.resolve("config.yml"))
val maxPlayers = config.value.maxPlayers

config.update { copy(maxPlayers = 50) }
config.save()
```

```java
@Configuration(header = "Shop settings")
record ShopConfig(@Comment("Items per page") @Range(min = 1, max = 54) int pageSize, Duration discount) {}

TypedConfig<ShopConfig> config = Configs.open(path, ShopConfig.class);
config.update(current -> new ShopConfig(36, current.discount()));
config.save();
```

The first run writes this file:

```yaml
# Example plugin configuration

# Maximum player count
max-players: 20

# Database connection
database:
  type: MARIADB
  host: localhost
  port: 3306
  ...
```

A configuration without a file is just an instance, `PluginConfig()`. `Configs.parse(text, ...)` and `Configs.read(stream, ...)` load from text, `Configs.encode(value)` writes text, and [`ConfigBinding`](Configuration#data-classes) binds sections of a path-based configuration.

## Supported types

Constructor defaults and nested classes need no extra annotations; `@Configuration` marks the root and carries the header. Supported values: strings, all numbers including `BigInteger` and `BigDecimal` at full precision, booleans, enums (case-insensitive), nullable values, lists, sets, arrays, maps, `UUID`, `Duration` (`30s`, `1h30m` or ISO-8601), `java.time` dates and times, and any type with a serializer registered on a custom `JsonCodec` (`TypedConfigOptions.Builder.codec`). Polymorphic types are only used when you annotate them with Jackson's `@JsonTypeInfo`.

Property names are written in kebab case: `maxPlayers` becomes `max-players`.

## Loading and saving

Every load and reload runs the same steps on a copy: read the file, parse it, run migrations, apply overrides, decode, validate. Only then does the new value replace the current one. A broken file or an invalid value throws `ConfigException` (`ConfigValidationException` lists every violation) and leaves the previous value and the file untouched.

- A missing file is created from the defaults.
- Missing values are added with their defaults (`writeDefaults(false)` turns this off); existing values are never replaced. The file is only rewritten when something was added, migrated or removed.
- `update { ... }` validates the new value and keeps it in memory. Nothing is written until `save()`.
- `save()` refuses to overwrite a file that was changed on disk since it was loaded and throws `ConfigConflictException`. Call `reload()` to take over the external change, or `save(true)` to replace it.
- `reloadIfModified()` reloads only when the file's modification time or size changed.
- `addListener { value -> ... }` is called after updates and reloads that changed the value.

## Unknown and removed keys

Keys in the file that no property reads, for example after a property was removed, are listed in `unknownKeys`. `unknownKeys(UnknownKeys.KEEP)` (default) leaves them in the file, `WARN` also logs them, and `REMOVE` deletes them when the file is written.

## Validation

| Annotation | Meaning |
| --- | --- |
| `@Range(min, max)` | number within bounds |
| `@Length(min, max)` | length of a text, size of a list, set or map |
| `@Pattern(regex)` | text matches completely |
| `@NotBlank` | text contains a non-whitespace character |
| `@Required` | value present and not `null` |

```text
Configuration validation failed:
  max-players: expected value between 1 and 100, got 500
  database.port: expected value between 1 and 65535, got 70000
```

Nested classes and list elements are validated too (`rewards[2].amount`). Add checks with `validator { value -> listOf(ConfigViolation("path", "message")) }`. Wrong types are reported with the file and path, for example `Invalid configuration in config.yml at 'database.port': ...`.

## Formats

The format follows the extension (`.yml`, `.yaml`, `.json`, `.toml`) or `format(...)`. The object API is the same for all three.

## Format limits

| | YAML | JSON | TOML |
| --- | --- | --- | --- |
| `@Comment` and header for new keys | written | not possible | not written |
| Comments written by people | kept | not possible | lost when the file is rewritten |
| `null` | stored | stored | see below |
| Key order | kept, new keys are appended | kept, new keys are appended | kept, but tables follow plain values as TOML requires |

**YAML comments.** When Qavlen rewrites a YAML file (new defaults, migrations, removed keys or `save()`), it carries every comment of the previous file over to the same key path: comments above a key, comments after a value on the same line, blank lines between entries, comments inside lists and at the end of a section or of the file. The header is the comment above the first key, so it is kept too. Limits that follow from how YAML parsers work:

- Comments of keys that are removed are removed with them.
- For keys that already exist, the file's comments win: a changed `@Comment` text in a new version only appears for keys the file does not have yet.
- Formatting is normalized: indentation becomes two spaces, list items are not indented below their key (`- item`), and quoting follows YAML's rules. Values are never changed by this.
- Comments inside flow collections (`[a, b]` or `{a: 1}`) cannot be attached to individual entries.

The same preservation applies to the path-based [Configuration](Configuration) API and is available as `preserveYamlComments(previous, updated)` (Java: `YamlComments.preserveYamlComments`).

**TOML and null.** TOML has no null. A null value is left out of the file only when reading the file back gives the same value, which is the case when the property's default is null as well. Otherwise `save()` fails with a `ConfigException` naming the paths, and the file stays unchanged; use a non-null value, a property whose default is null, or YAML or JSON. Lists cannot contain null in TOML at all. A null default is not treated as missing, so TOML files are not rewritten on every load.

## Overrides and sources

Values are taken, from lowest to highest priority, from the class defaults, the file, `overrides(map)`, system properties and environment variables:

```kotlin
val config = Configs.open<PluginConfig>(path) {
    systemProperties("myplugin")   // -Dmyplugin.database.port=5432
    environment("MYPLUGIN")        // MYPLUGIN_DATABASE_PASSWORD=...
}
```

Overrides only cover simple values (not lists or maps) and are never written to the file: `save()` and the defaults written on load use the file's own values. An `update` cannot change an overridden property; the override keeps its value.

## Binding a section

`prefix("database")` binds only that section. The rest of the file is kept, but rewritten in a normalized form when the section is saved, and `@Comment` texts are only written for whole-file configurations.

## Migrations

```kotlin
Configs.open<PluginConfig>(path) {
    version(2)
    migration(1) { section ->
        section.set("database.host", section.get("db-host"))
        section.remove("db-host")
    }
}
```

Migrations run on the raw tree before decoding, are saved independently of `writeDefaults`, and the previous file is copied to `config.yml.v1-<timestamp>.bak` first.
