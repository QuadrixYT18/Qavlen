Qavlen is written in Kotlin and uses explicit API mode, so every public declaration has a declared type and visibility.

- Builders accept `java.util.function` types, which Kotlin lambdas convert to automatically: `command("coins") { it.permission("coins.use") }`.
- Reified helpers avoid class literals: `context.argument<Int>("amount")`, `context.sender<Player>()`, `codec.decode<List<Item>>(json)`.
- `placeholder("name", value)` creates a translation placeholder.
- `ConfigOptions { defaults(...); version(2) }` builds configuration options.
- Nullable parameters mark optional command arguments together with `@Optional`.

Annotated commands are processed by KSP:

```kotlin
plugins {
    kotlin("jvm") version "2.4.21"
    id("com.google.devtools.ksp") version "2.3.14"
}

dependencies {
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-commands:VERSION")
    ksp("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION")
}
```

Suspend functions are not supported as command methods. Start coroutines from the executor if you need them.
