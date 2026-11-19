`qavlen-storage` provides pooled JDBC access with HikariCP. Add the JDBC driver yourself.

```kotlin
val database = Database.mariadb("localhost", 3306, "game")
    .credentials(user, password)
    .poolSize(8)
    .forbidBlockingWhen(Bukkit::isPrimaryThread)
    .connect()

Migrations(database)
    .add(1, "create players", "CREATE TABLE players (uuid CHAR(36) PRIMARY KEY, coins BIGINT NOT NULL)")
    .migrate()

database.queryFirstAsync("SELECT coins FROM players WHERE uuid = ?", { it.getLong(1) }, player.uniqueId.toString())
    .thenAccept { coins -> ... }
```

```java
Database database = Database.postgresql("localhost", 5432, "game").credentials(user, password).connect();
List<String> names = database.query("SELECT name FROM players WHERE coins > ?", row -> row.getString(1), 100);
```

- All values are bound as prepared statement parameters; nothing is concatenated into SQL. Enums are bound by name and `Instant` as timestamp.
- `query`, `queryFirst`, `update`, `insert` (returns the generated key) and `batch` exist synchronously and as `...Async` variants returning `CompletableFuture`.
- `transaction { session -> ... }` commits when the block returns and rolls back when it throws.
- Asynchronous calls run on a thread pool sized like the connection pool, so waiting work queues in memory instead of occupying threads.
- `forbidBlockingWhen` makes synchronous calls throw while the check is true, which catches accidental queries on a game thread.
- `queryTimeout(Duration)` cancels statements that run longer; without it, statements have no time limit.
- `close()` waits for running queries and closes the pool. Asynchronous calls after that complete exceptionally.
- `driver(className)` loads a driver explicitly, which helps in plugin class loaders.

## Migrations

Migrations are recorded in `qavlen_schema_history` with a checksum. Each runs in its own transaction; an edited migration that was already applied is reported. PostgreSQL rolls back failed DDL, MariaDB commits DDL implicitly, so keep MariaDB migrations small.

There is no ORM; map rows with `RowMapper`.
