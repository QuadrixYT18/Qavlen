`qavlen-serialization` wraps Jackson 3 with defaults suited for application data.

```kotlin
val codec = JsonCodec.default
val json = codec.encode(profile)
val profile = codec.decode(json, Profile::class.java)
val items = codec.decode<List<Item>>(json)      // Kotlin
List<Item> items = codec.decodeList(json, Item.class); // Java
```

- Kotlin data classes (including default values and null-safety), records and Java beans work without annotations.
- `UUID` is written in canonical form; `Instant`, `Duration` and other `java.time` types as ISO-8601 strings.
- Unknown properties are ignored; missing non-null Kotlin properties and `null` for primitives are errors.
- Errors are `SerializationException`s with a readable `path` such as `players[2].uuid`.

```kotlin
val custom = JsonCodec.default.toBuilder()
    .stringType(NamespacedKey::class.java, NamespacedKey::asString) { NamespacedKey.fromString(it)!! }
    .prettyPrint(true)
    .build()
```

The module also binds configuration sections to classes, see [Configuration](Configuration#data-classes). It depends on `qavlen-config` for that.

`propertyNaming` and `caseInsensitiveEnums` adjust naming conventions. `stringType` covers values stored as strings. For anything else, register a Jackson module with `module(...)`.
