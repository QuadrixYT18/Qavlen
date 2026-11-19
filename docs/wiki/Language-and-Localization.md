`qavlen-language` resolves translation keys for a `java.util.Locale` and formats placeholders with the JDK's locale-aware formatters.

```kotlin
val translations = Translations.builder()
    .defaultLocale(Locale.ENGLISH)
    .resources(javaClass.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN)) // bundled files
    .directory(dataFolder.resolve("lang"))                                          // server owner overrides
    .build()

translations.format(player.locale(), "shop.bought", placeholder("item", "Diamond"), placeholder("price", 1250))
```

## Files

Translation files are YAML, JSON or `.properties`, named after the locale tag: `en.yml`, `de_DE.yml` or `de-DE.json`. Nested YAML and JSON keys become dotted keys; a list becomes a multi-line message.

`directory(path)` reads files directly in the directory into the default namespace and files in subdirectories into a namespace named after the subdirectory: `lang/shop/de.yml` defines `shop:<key>`. Resources cannot be listed portably, so `resources(...)` takes the locales explicitly. Later sources override earlier ones, which lets server owners override bundled text.

## Fallbacks

A lookup for `de_AT` tries `de_AT`, `de`, the default locale and the root locale. A missing key formats as the key itself, so it is visible during testing.

## Placeholders

| Syntax | Result |
| --- | --- |
| `{player}` | value by type: numbers with grouping, dates and times in medium style, durations as `1h30m` |
| `{price:number:#,##0.00}` | `DecimalFormat` pattern |
| `{ratio:percent}`, `{amount:integer}`, `{price:currency}` | `NumberFormat` variants |
| `{when:date:short}`, `{when:time}`, `{when:datetime:long}` | `DateTimeFormatter` localized styles |
| `{wait:duration}` | compact duration |
| `{count:choice:0#no items\|1#one item\|1<{count} items}` | `ChoiceFormat` for plural forms |
| `{{` and `}}` | literal braces |

Placeholder values are inserted literally; a value containing `{other}` is not expanded. `Instant` and `Date` use the zone configured with `zone(...)` (default: system zone). Malformed templates are reported with their file and key when loading.

## Validation and reload

- `missingKeys(locale)` lists keys of the default locale that a locale must fall back for.
- `validate()` reports missing translations and placeholders that do not exist in the default locale.
- `reload()` re-reads all sources and swaps them atomically. If a file is broken, the previous translations stay active.
- `localizations(key)` returns the text of a key for every locale that defines it, for example for Discord command localizations.

## Rich text

[`qavlen-adventure`](Adventure) renders translations as Adventure components with MiniMessage. Translation files are trusted markup; placeholder values are always inserted as plain text, so a player name like `<click:run_command:/op me>` cannot inject tags. `Component` values keep their style. Every message can use `<prefix>`, which renders the `prefix` key.

```kotlin
val messages = AdventureMessages(translations)
messages.send(player, "shop.bought", placeholder("item", item.displayName()))
messages.title(player, "event.title", "event.subtitle")
messages.actionBar(player, "event.countdown", placeholder("seconds", 5))
```

The locale is taken from the audience (`Identity.LOCALE`), which is the client language on Paper and Velocity.
