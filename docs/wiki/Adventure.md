Qavlen renders MiniMessage texts as [Adventure](https://docs.advntr.dev) components. Texts stored in a configuration need only `qavlen-config`; translations add `qavlen-language`.

## Dependencies

Qavlen compiles against Adventure but does not bring it along: `compileOnly` means it is not part of Qavlen's published dependencies.

| Where your code runs | What you add |
| --- | --- |
| Paper, Folia, Velocity | nothing; the server provides Adventure and MiniMessage. Do not shade them into your plugin |
| Standalone JVM application or Discord bot | Adventure and MiniMessage as `implementation`, in the version below or newer |
| Configurations without components | nothing; `getString`, `getInt` and every other getter work without Adventure |

```kotlin
dependencies {
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-config:VERSION")
    implementation("net.kyori:adventure-api:5.2.0")
    implementation("net.kyori:adventure-text-minimessage:5.2.0")
}
```

Calling `getComponent` or `MessageComponents` without Adventure fails with `NoClassDefFoundError`. Reflection or mocking libraries that inspect every method of `ConfigSection` (for example Mockito in your tests) also need Adventure on the test classpath.

## Components from configurations

Every `ConfigSection` can render its values. That includes `YamlConfig`, `JsonConfig` and sections returned by `getSection`.

```yaml
prefix: "<gray>[Shop]</gray>"
welcome: "<prefix> <gradient:gold:yellow>Welcome, {player}!</gradient> You have {coins:number} coins."
lore:
  - "<gray>Price: {price}"
  - "<yellow>Click to buy"
```

```kotlin
val config = YamlConfig.load(path)

config.getString("welcome")                                  // the raw text
val message: Component = config.getComponent("welcome")      // ConfigException when the key is missing
player.sendMessage(config.getComponent("welcome", "player" to player.name, "coins" to 1500))
config.getComponentOrNull("motd")                            // null when the key is missing, for optional texts
config.getComponentList("lore", "price" to 250)              // one component per list entry or line
```

```java
player.sendMessage(config.getComponent("welcome", Map.of("player", player.getName())));
Component optional = config.getComponentOrNull("motd");      // null when missing
List<Component> lore = config.getComponentList("lore", Map.of("price", 250));
```

The rules for each case:

| Situation | Result |
| --- | --- |
| Key missing | `getComponent` and `getComponentList` throw `ConfigException("Missing message '<path>'")`; `getComponentOrNull` returns `null`. A missing message never turns into an empty one |
| Value is a section or list (for `getComponent`) | `ConfigValueException` naming the path |
| Malformed placeholder such as `{player` | `ConfigException("Invalid message at '<path>': ...")` |
| Placeholder without a value | stays visible as `{player}` |
| `{{` and `}}` | literal braces |

Values are formatted with `Locale.ROOT`, so `{coins:number}` gives `1,500` on every machine. All styles from [Language & Localization](Language-and-Localization) work, including dates, durations and `choice` plurals. Use translations when the format should follow the player's language.

Placeholder values can be strings, numbers, dates, durations or `ComponentLike`. Java callers pass a `Map`; Kotlin callers can also use `"name" to value` pairs.

`<prefix>` renders the nearest `prefix` key, searched from the section containing the message up to the root. This works the same for `config.getComponent("messages.staff.alert")` and `config.getSection("messages.staff").getComponent("alert")`. A missing prefix renders as nothing, so you can remove the prefix by deleting its key:

```yaml
prefix: "<gray>[Shop]</gray>"
messages:
  join: "<prefix> {player} joined"        # [Shop] Alex joined
  staff:
    prefix: "<red>[Staff]</red>"
    alert: "<prefix> {text}"              # [Staff] ...
```

## Texts without a file

`MessageComponents` renders any text with the same rules. For example, it renders a `String` field of a [typed configuration](Typed-Configuration):

```kotlin
val motd = MessageComponents.render(config.value.motd, mapOf("online" to online))
```

```java
Component motd = MessageComponents.render(config.value().motd(), Map.of("online", online));
```

## Trusted markup, untrusted values

Texts in configurations and translation files are MiniMessage written by the server owner, so tags, colors, gradients, hover and click events all work. Placeholder values are never parsed as MiniMessage. Each placeholder becomes a generated tag with unparsed content. A player named `<click:run_command:/op me>x` therefore appears literally and cannot add click events, hover text or colors, and cannot close the generated tag.

Values that are Adventure components (`ComponentLike`), such as an item's display name, are inserted with their style intact.

## Translations

`AdventureMessages` renders [translations](Language-and-Localization) per locale. It ships with `qavlen-language`.

```kotlin
val messages = AdventureMessages(translations)
messages.send(player, "shop.bought", placeholder("item", item.displayName()), placeholder("price", 1250))
messages.actionBar(player, "event.countdown", placeholder("seconds", 5))
messages.title(player, "event.title", "event.subtitle", mapOf("round" to 3))
val component = messages.render(Locale.GERMAN, "shop.header")
val lore = messages.renderLines(locale, "shop.lore")
messages.sendText(player, config.value.messages.welcome, placeholder("player", player.name))
```

```java
AdventureMessages messages = new AdventureMessages(translations);
messages.send(player, "shop.bought", Placeholder.of("item", "Diamond"));
```

Translation values work like configuration texts: `<prefix>` renders the translation key `prefix` for the same locale, and a missing key renders as the key itself. Values are formatted for the locale. `send`, `actionBar` and `title` use the audience's `Identity.LOCALE`. That is the client language for players on Paper and Velocity, and the translations' default locale for the console. `locale(audience)` returns the same value for your own rendering.

## Migrating from `qavlen-adventure`

Up to 0.4.0, `AdventureMessages` lived in the separate artifact `qavlen-adventure`, and `MessageTemplate` and `ValueFormatter` lived in `qavlen-language`. Since 0.5.0, each class exists in exactly one artifact:

| Class | Artifact since 0.5.0 |
| --- | --- |
| `de.quadrixyt.qavlen.adventure.AdventureMessages` | `qavlen-language` |
| `de.quadrixyt.qavlen.config.MessageComponents`, `PlaceholderFormatter` | `qavlen-config` |
| `de.quadrixyt.qavlen.language.MessageTemplate`, `ValueFormatter` | `qavlen-config` (included by `qavlen-language`) |
| `de.quadrixyt.qavlen.language.Translations`, `Placeholder` | `qavlen-language` |

To migrate, update the dependencies; your code does not change:

```kotlin
dependencies {
    // before
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-adventure:v0.4.0")
    // after: translations and AdventureMessages
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-language:VERSION")
    // or, for components from configurations only
    implementation("com.github.QuadrixYT18.Qavlen:qavlen-config:VERSION")
}
```

On Paper and Velocity, remove `qavlen-adventure` without replacement: `qavlen-paper` and `qavlen-velocity` already include `qavlen-language`. Package and class names are unchanged, so imports and code compiled against 0.4.0 keep working.

If `qavlen-adventure` is still declared, Gradle stops with a capability conflict, because `qavlen-language` declares that it replaces it:

```
Cannot select module because of conflict with ...:qavlen-language:... Both provide capability '...:qavlen-adventure:...'
```

Remove the `qavlen-adventure` line to fix it. Maven cannot detect this conflict. Remove `qavlen-adventure` by hand there, and check with `mvn dependency:tree` that it is gone.
