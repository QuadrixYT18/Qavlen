Qavlen is published through [JitPack](https://jitpack.io/#QuadrixYT18/Qavlen). Every module is a separate artifact named `qavlen-<module>`; depend only on the modules you use.

```kotlin
repositories {
    mavenCentral()
    maven("https://jitpack.io")
}

dependencies {
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-paper:VERSION")
    ksp("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION") // Kotlin
    // annotationProcessor("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION") // Java
}
```

`VERSION` is a release tag or a commit hash. Kotlin projects apply the KSP plugin (`com.google.devtools.ksp`) for annotation processing.

## Platform dependencies

Platform APIs are not bundled. Declare them yourself as `compileOnly`:

| Module | Required by you |
| --- | --- |
| `qavlen-paper` | `io.papermc.paper:paper-api` (repository `https://repo.papermc.io/repository/maven-public/`) |
| `qavlen-velocity` | `com.velocitypowered:velocity-api` |
| `qavlen-jda` | `net.dv8tion:JDA` (as `implementation`; bots run on their own) |
| `qavlen-adventure` | Adventure and MiniMessage, provided by Paper and Velocity |
| `qavlen-storage` | the JDBC driver, for example `org.mariadb.jdbc:mariadb-java-client` or `org.postgresql:postgresql` |

## Shading plugins

Plugins must bundle Qavlen and relocate it, so two plugins with different Qavlen versions cannot interfere. With the [Shadow plugin](https://gradleup.com/shadow/):

```kotlin
plugins {
    id("com.gradleup.shadow") version "9.6.1"
}

tasks.shadowJar {
    archiveClassifier.set("")
    relocate("de.quadrixyt.qavlen", "com.example.myplugin.libs.qavlen")
    relocate("org.yaml.snakeyaml", "com.example.myplugin.libs.snakeyaml")
    relocate("com.google.gson", "com.example.myplugin.libs.gson")
    mergeServiceFiles()
}
```

Keep platform APIs `compileOnly` so they are never copied into the plugin jar. Kotlin plugins on Paper can let the server download the standard library through `libraries` in `plugin.yml` and exclude `org.jetbrains.kotlin` from the shadow jar; Velocity plugins bundle and relocate it. The [examples](https://github.com/QuadrixYT18/Qavlen/tree/master/examples) contain working configurations for both.
