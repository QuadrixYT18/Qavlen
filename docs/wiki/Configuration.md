`qavlen-config` reads and writes YAML (SnakeYAML) and JSON (Gson) documents through paths such as `database.port`. To bind a whole file to a data class or record, with comments, TOML, validation annotations and overrides, see [Typed Configuration](Typed-Configuration).

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

## Bundled default files

Put the default file into `src/main/resources` and pass a class loader. Qavlen takes the resource with the same file name as the target, for example `messages.yml`:

```kotlin
val messages = YamlConfig.load(dataFolder.toPath().resolve("messages.yml"), javaClass.classLoader)
val config = YamlConfig.load(path = dataFolder.toPath().resolve("config.yml"), resourceOwner = MyPlugin::class.java)
```

```java
YamlConfig messages = YamlConfig.load(getDataFolder().toPath().resolve("messages.yml"), getClass().getClassLoader());
YamlConfig config = YamlConfig.load(getDataFolder().toPath().resolve("config.yml"), MyPlugin.class);
```

`JsonConfig.load` works the same way. Neither variant needs `saveResource()` or `getResourceAsStream()`, and `qavlen-config` does not depend on Paper, Velocity or any other platform. Every plugin passes its own class loader.

| | `load(path)` | `load(path, classLoader)` |
| --- | --- | --- |
| File missing | Created from `ConfigOptions` defaults, or empty | Created as an exact copy of the resource, with comments, blank lines and formatting |
| File exists | Missing `ConfigOptions` defaults are added | Missing keys of the resource are added, with the resource's comments for them |
| Resource missing | Not looked up | `ConfigException` naming the resource; no file is created |

Updating an existing file follows these rules:

- **Existing values:** values in the file are never replaced. Lists are kept as they are, and an explicit `null` counts as a value, not as a missing key.
- **Unknown keys:** keys the resource no longer has stay in the file.
- **Nested sections:** missing keys inside sections are added in the right place.
- **Type conflicts:** if the file has a value where the resource has a section, or the other way round, loading fails with a `ConfigException` naming the key. Nothing is written.
- **Comments and quotes:** comments in the file stay, and so do quotes. New keys receive the comments and quotes the resource has for them, while comments you deleted from existing keys do not come back.
- **Writes:** the file is written atomically, and only when keys were added. It is not written if it cannot be parsed, or if it changes on disk while it is being loaded.

`ConfigOptions` still apply. Validators, migrations and additional defaults work together with the resource; with `writeDefaults(false)`, added keys stay in memory only.

TOML is not a format of `qavlen-config`; [typed configurations](Typed-Configuration#bundled-default-files) support it, including bundled default files.

## Reading values

Paths are dot-separated. Typed getters (`getString`, `getInt`, `getLong`, `getDouble`, `getBoolean`, `getList`, `getStringList`, `getIntList`, `getMap`) return `null` when the value is absent, or the given default. A value of the wrong type throws `ConfigValueException` naming the path, so a typo such as `port: 33o6` is reported instead of silently replaced. `getSection` returns a live view of a nested section.

## Messages and components

Values can be rendered as MiniMessage components with injection-safe placeholders. Adventure must be on the classpath; see [Adventure & MiniMessage](Adventure) for the dependency and the exact rules.

```kotlin
player.sendMessage(config.getComponent("messages.welcome", "player" to player.name))  // ConfigException when missing
config.getComponentOrNull("messages.motd")                         // null when missing
config.getComponentList("shop.lore", "price" to 250)            // placeholders as {price} or <price>
```

```java
player.sendMessage(config.getComponent("messages.welcome", Map.of("player", player.getName())));
```

## Data classes

With `qavlen-serialization` on the classpath, sections can be read into Kotlin data classes, records or Java beans:

```kotlin
data class DatabaseSettings(
    val host: String = "localhost",
    val port: Int = 3306,
    val poolSize: Int = 4,
    val timeout: Duration = Duration.ofSeconds(10),
)

val database = config.bind<DatabaseSettings>("database")
```

```java
record Rewards(int dailyCoins, Duration cooldown, List<String> worlds) {}

Rewards rewards = ConfigBinding.bind(config, "rewards", Rewards.class);
```

```yaml
database:
  host: db.internal
  pool-size: 8
  timeout: 30s
rewards:
  daily-coins: 50
  cooldown: 20h
  worlds: [world, world_nether]
```

- Property names use kebab case in the file: `poolSize` is `pool-size`.
- Durations accept `30s`, `1h30m` or ISO-8601 and are written in the compact form.
- Enum constants ignore case.
- Kotlin default values fill missing keys; a missing section binds as empty, so all defaults apply. A value without a default fails.
- Invalid values fail with a `ConfigException` naming the full path, for example `Invalid configuration at 'database.port'`.
- Unknown keys are ignored.

`ConfigBinding.toMap(DatabaseSettings())` turns an instance into defaults for `ConfigOptions.defaults`, and `ConfigBinding.write(config, "database", settings)` stores one back before `save()`. Binding uses `ConfigBinding.codec`; pass your own `JsonCodec` to any method to change the conventions.

## Defaults

Defaults only add missing keys; existing values are never replaced. Missing keys are written back to the file. If the file has a value where the defaults have a section, or the other way round, loading fails with a `ConfigException` that names the key instead of discarding data. For a default file in `src/main/resources`, see [Bundled default files](#bundled-default-files).

## Safety

- A file that cannot be parsed is never overwritten.
- Loading and `reload()` parse, migrate, merge and validate a copy first. If any step fails, a `ConfigException` is thrown and the previous values stay in place.
- `save()` validates, writes atomically and skips the write when the content did not change. `isDirty` tells whether there are unsaved changes.
- **`save()` keeps edits made to the file while the program runs.** It merges them with the changes in memory, and the configuration takes them over:

  | Since loading | Result of `save()` |
  | --- | --- |
  | The program changed `a`, someone edited `b` in the file | both changes end up in the file |
  | Only the file was edited | the file stays as it is; its values become current |
  | Both changed the same value | the file's value wins; the discarded change is logged with its path |
  | The edit broke the file | nothing is written; `ConfigConflictException` |

  Comments added to the file stay as well. `save(true)` replaces the file with the values in memory. Listeners receive the taken-over values as a reload. `ConfigMerge.merge(base, file, memory)` exposes the same merge.
- Duplicate keys are rejected in YAML and JSON.
- Values are kept as written: unquoted YAML dates stay text, and numbers that do not fit `Long` or `Double` exactly are kept as `BigInteger` or `BigDecimal`.
- An explicit `null` is a stored value: `contains` returns `true`, getters return `null`, and defaults do not replace it. JSON parsing is strict (no comments or trailing commas). YAML alias expansion is limited.
- **Quotes are kept:** a value or key written as `"text"`, `'text'` or a `|` block keeps that style when the file is rewritten, also after its value changed. New keys from a [bundled default file](#bundled-default-files) take the quotes they have there.
- **YAML comments are kept** when Qavlen rewrites a file, see [Format limits](Typed-Configuration#format-limits). Files are only rewritten when defaults or migrations changed them or when you call `save()` after changes.

## Validation

`ConfigValidator` returns a list of `ConfigViolation(path, message)`. All violations are reported together in a `ConfigValidationException`. `ConfigValidator.required(...)` and `ConfigValidator.range(...)` cover common cases.

## Schemas

A `ConfigSchema` declares the expected structure and reports every deviation at once, including keys nobody reads, which are usually typos:

```kotlin
val schema = ConfigSchema.builder()
    .required("database.host", ConfigType.STRING)
    .optional("database.port", ConfigType.INTEGER).range("database.port", 1, 65535)
    .optional("mode", ConfigType.STRING).oneOf("mode", "fast", "safe")
    .optional("rewards", ConfigType.SECTION)
    .rejectUnknownKeys()
    .build()

val config = YamlConfig.load(path, ConfigOptions { schema(schema) })
```

```text
Configuration validation failed:
  database.host: is required
  database.port: must be between 1 and 65535
  database.hots: is not a known setting
```

Keys inside declared sections and lists are always accepted, and the migration version key is accepted automatically.

## Change listeners and hot reload

```kotlin
val subscription = config.addListener { change ->
    if (change.affects("database")) reconnect()
}
```

Listeners are called after `set` and `remove` (also through section views) with the changed path, and after a successful `reload()` with every path whose value differs. A failed reload changes nothing and calls no listener. Listeners run outside the configuration's lock, may read and write the configuration, and a failing listener is logged without affecting the others. `subscription.close()` removes the listener.

`reloadIfModified()` reloads only when the file's modification time or size changed since it was last read or written, which makes polling from a repeating task cheap:

```kotlin
scheduler.asyncTimer(Duration.ofSeconds(5), Duration.ofSeconds(5)) { config.reloadIfModified() }
```

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

The version is stored under `config-version` (configurable); a file without it is version 1. Migrations run in order on a copy. Before the migrated file is written, the original is copied to `config.yml.v<old>-<timestamp>.bak` (disable with `backups(false)`); existing backups are never replaced. A migrated file is saved even with `writeDefaults(false)`, so migrations run only once. Files with a newer version than the code supports are rejected.

## Streams

`YamlConfig.read(InputStream)`, `YamlConfig.parse(String)` and the JSON equivalents create documents without a backing file. Use `saveTo(path)` or `writeTo(writer)` to write them.
