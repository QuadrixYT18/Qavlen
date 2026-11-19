# Paper example (Kotlin)

A Paper plugin with Brigadier commands, MiniMessage translations with a prefix, a paginated menu, persistent data and Folia-aware scheduling.

```shell
./gradlew :examples:paper-kotlin:shadowJar
```

Copy `build/libs/paper-kotlin.jar` into the server's `plugins` folder. The Kotlin standard library is not bundled; Paper downloads it through the `libraries` entry in `plugin.yml`. Qavlen and its dependencies are relocated below `com.example.qavlen.libs`.
