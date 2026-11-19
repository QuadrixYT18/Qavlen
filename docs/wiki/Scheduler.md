`qavlen-scheduler` runs delayed and repeating tasks in plain JVM applications and bots.

```kotlin
TaskScheduler("backups", threads = 1).use { scheduler ->
    val task = scheduler.runRepeating(Duration.ZERO, Duration.ofMinutes(5)) { backup() }
    scheduler.runLater(Duration.ofSeconds(30)) { announce() }
    task.cancel()
}
```

- `runLater`, `runRepeating` (fixed rate) and `runWithFixedDelay` return a `ScheduledTask` that can be cancelled.
- An exception in a repeating task is passed to the error handler and the task keeps running. A plain `ScheduledExecutorService` would silently stop it.
- Threads are named daemon threads; `close()` waits up to five seconds for running tasks.

Minecraft servers have their own threading rules. Use `PaperScheduler` on Paper and Folia, and Velocity's scheduler on Velocity.
