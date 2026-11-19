`qavlen-network` is a small JSON REST client on top of `java.net.http`.

```kotlin
val api = RestClient.builder("https://api.example.com/v1/")
    .header("Authorization", "Bearer $token")
    .requestTimeout(Duration.ofSeconds(10))
    .build()

val user = api.get("users/42").query("fields", "name,avatar").sendJson(User::class.java)
api.post("events").json(Event("join", uuid)).send().thenAccept { result -> result.requireSuccess() }
```

```java
try (RestClient api = RestClient.builder("https://api.example.com/v1/").header("Authorization", "Bearer " + token).build()) {
    User user = api.get("users/42").query("fields", "name").sendJson(User.class).join();
}
```

- Every request has a connect and request timeout.
- Query parameters are URL-encoded; JSON bodies use `JsonCodec`.
- `send()` completes with an `HttpResult` for any status; `sendJson` fails with `HttpStatusException` for non-2xx responses.
- `RetryPolicy` retries only idempotent methods (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`) after connection errors, timeouts and 429/502/503/504 responses, with exponential backoff, `Retry-After` support and an upper bound. `RetryPolicy.NONE` disables retries.

Responses are buffered in memory up to `maxResponseSize` (16 MiB by default); larger bodies fail with `HttpResponseTooLargeException` and are not retried. Use `java.net.http` directly for large downloads or streaming.

`RestClient` is `AutoCloseable`. Close it when it is no longer needed, for example in a plugin's `onDisable`, to release its threads.
