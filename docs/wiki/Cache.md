`qavlen-cache` provides `LocalCache`, an in-memory cache backed by Caffeine, behind the `Cache` interface that [Redis](Redis) implements as well.

```kotlin
val profiles = LocalCache.builder()
    .maximumSize(10_000)
    .expireAfterWrite(Duration.ofMinutes(10))
    .build<UUID, Profile>()

val profile = profiles.get(uuid) { loadProfile(it) }
profiles.getAsync(uuid) { database.queryFirstAsync(...) }
```

- `get(key, loader)` computes missing values once; concurrent callers wait for the same computation. `null` results are not cached.
- `getAsync` shares one load per key between concurrent callers. Failed loads are not cached.
- `expireAfterWrite`, `expireAfterAccess` and `maximumSize` control eviction; Caffeine evicts by a frequency-aware policy.
- `stats()` reports hits, misses and evictions.
