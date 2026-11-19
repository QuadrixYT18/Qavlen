`qavlen-cache-redis` implements `Cache` with Redis through Jedis, for data shared between servers or processes.

```kotlin
val redis = RedisClient.create("redis://localhost:6379")
val sessions = RedisCache.builder<UUID, Session>(redis, "sessions", Session::class.java)
    .expireAfterWrite(Duration.ofMinutes(30))
    .build()
```

- Values are stored as JSON with `JsonCodec` under `<namespace>:<key>`. Use a namespace per cache.
- TTLs are set atomically with the value and rounded up to whole seconds.
- `getAsync` runs Redis calls on virtual threads and shares concurrent loads of the same key within the process.
- `invalidateAll` deletes the namespace with `SCAN`, which does not block Redis like `KEYS`.
- The client is owned by the caller; close it on shutdown.

Redis is optional. Local caches never require it.
