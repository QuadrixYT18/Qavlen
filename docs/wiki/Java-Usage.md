Every API is designed to be called from Java without Kotlin function types, `Pair` or `Unit`.

- Factories are static: `YamlConfig.load(path)`, `Translations.builder()`, `CommandDefinition.builder("coins")`, `Database.mariadb(...)`.
- Optional parameters have overloads (`@JvmOverloads`).
- Kotlin properties are getters: `source.getName()`, `context.getSource()`.
- Asynchronous methods return `CompletableFuture`.
- Callbacks are functional interfaces such as `CommandExecutor`, `RowMapper`, `ConfigValidator` and `ClickHandler`.

Annotated commands use the javac processor:

```kotlin
dependencies {
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-commands:VERSION")
    annotationProcessor("com.github.QuadrixYT18.Qavlen:qavlen-commands-processor:VERSION")
}
```

```java
@Command(name = "greet", aliases = {"hello"})
public final class GreetCommand {
    @Subcommand(path = "user")
    public void user(CommandContext context, String name, @Optional Style style) {
        context.replyTranslated("greet.friendly", Placeholder.of("name", name));
    }
}
```

Command classes and methods must be `public`; nested command classes must be `static`. Optional arguments use wrapper types such as `Integer`. The processor registers itself as an isolating processor, so Gradle keeps incremental compilation.
