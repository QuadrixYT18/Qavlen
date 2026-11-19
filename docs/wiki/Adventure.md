`qavlen-adventure` renders [translations](Language-and-Localization) as [Adventure](https://docs.advntr.dev) components with MiniMessage. Paper and Velocity provide Adventure at runtime, so the module declares it as `compileOnly`; add `net.kyori:adventure-api` and `adventure-text-minimessage` yourself if you use it elsewhere.

```kotlin
val messages = AdventureMessages(translations)
messages.send(player, "shop.bought", placeholder("item", item.displayName()), placeholder("price", 1250))
messages.actionBar(player, "event.countdown", placeholder("seconds", 5))
messages.title(player, "event.title", "event.subtitle", mapOf("round" to 3))
val component = messages.render(Locale.GERMAN, "shop.header")
val lore = messages.renderLines(locale, "shop.lore")          // one component per line
```

```java
AdventureMessages messages = new AdventureMessages(translations);
messages.send(player, "shop.bought", Placeholder.of("item", "Diamond"));
```

## Trusted markup, untrusted values

Translation values are MiniMessage written by the server owner:

```yaml
prefix: "<gradient:#5e9cff:#9b6bff>Shop</gradient> <dark_gray>»</dark_gray>"
shop:
  bought: "<prefix> <green>You bought <white>{item}</white> for <gold>{price}</gold> coins."
```

Placeholder values are never parsed as MiniMessage. Each placeholder becomes an unparsed tag, so a player named `<click:run_command:/op me>x` appears literally and cannot add click events, hover text or colors. Values are formatted for the locale first (numbers, dates, durations, plural choices) as described in [Language & Localization](Language-and-Localization).

Values that are Adventure components (`ComponentLike`), such as an item's display name, are inserted with their style intact.

## Prefix

Any message may contain `<prefix>`. It renders the translation key `prefix` (configurable in the constructor) for the same locale, or nothing when the key is missing. `<prefix>` is not expanded inside the prefix itself.

## Locale

`send`, `actionBar` and `title` use the audience's `Identity.LOCALE`, which is the client language for players on Paper and Velocity, and the default locale of the translations for the console. `locale(audience)` returns the same value for your own rendering.

A missing key renders as the key itself.
