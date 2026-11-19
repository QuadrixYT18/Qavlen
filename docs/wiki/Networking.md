`qavlen-network` is a small JSON REST client on top of `java.net.http`.

```kotlin
val api = RestClient.builder("https://api.example.com/v1/")
    .header("Authorization", "Bearer $token")
    .requestTimeout(Duration.ofSeconds(10))
    .build()

val user = api.get("users/42").query("fields", "name,avatar").sendJson(User::class.java)
api.post("events").json(Event("join", uuid)).send().thenAccept { result -> result.requireSuccess() }
```

- Every request has a connect and request timeout.
- Query parameters are URL-encoded; JSON bodies use `JsonCodec`.
- `send()` completes with an `HttpResult` for any status; `sendJson` fails with `HttpStatusException` for non-2xx responses.
- `RetryPolicy` retries only idempotent methods (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`) after connection errors, timeouts and 429/502/503/504 responses, with exponential backoff, `Retry-After` support and an upper bound. `RetryPolicy.NONE` disables retries.

Responses are buffered in memory; use `java.net.http` directly for large downloads or streaming.
