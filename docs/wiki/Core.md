`qavlen-core` is deliberately small and has no dependencies besides the Kotlin standard library.

## ResourceScope

Owns `AutoCloseable` resources and closes them once, in reverse registration order. A failure while closing one resource does not prevent the others from closing; later failures are added as suppressed exceptions.

```kotlin
val scope = ResourceScope()
val database = scope.own(Database.postgresql("localhost", 5432, "app").connect())
val scheduler = scope.own(TaskScheduler())
// on shutdown
scope.close()
```

Resources registered after `close()` are closed immediately.

## writeAtomically

`writeAtomically(path, text)` (Java: `QavlenFiles.writeAtomically`) writes to a temporary file in the same directory, flushes it to disk and moves it over the target, so readers never see a partially written file and a crash leaves either the old or the new content. The target's POSIX permissions are kept, and a symbolic link is written through instead of being replaced. If the file system does not support atomic moves, a regular replace is used.

## Durations

`Durations.parse("1h30m")` accepts compact durations with the units `w`, `d`, `h`, `m`, `s` and `ms`, separated by optional spaces, as well as ISO-8601 (`PT5M`). `Durations.format` produces the same compact form. Negative and malformed input is rejected with `IllegalArgumentException`.
