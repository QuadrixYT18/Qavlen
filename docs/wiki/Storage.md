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

## Settings in configurations

`DatabaseSettings` holds everything needed to connect and can be part of a [typed configuration](Typed-Configuration):

```kotlin
@Configuration
data class PluginConfig(val database: DatabaseSettings = DatabaseSettings(database = "coins"))

val config = Configs.open<PluginConfig>(path) { environment("COINS") } // COINS_DATABASE_PASSWORD stays out of the file
val database = Database.connect(config.value.database)
```

`type` selects MariaDB, PostgreSQL or SQLite and the default port; `url` replaces the generated JDBC URL, for example for H2. `toString()` never prints the password. `Database.builder(settings)` returns a builder for further options.

## SQLite

With `DatabaseType.SQLITE`, `database` is the path of the database file; host, port, username and password are ignored. The pool uses a single connection, because SQLite allows only one writer at a time. Add `org.xerial:sqlite-jdbc` as driver.

```kotlin
val database = Database.connect(DatabaseSettings(type = DatabaseType.SQLITE, database = dataFolder.resolve("data.db").toString()))
```

## Switching databases

`DatabaseMigrationManager` copies all data when the configured database changes, for example from SQLite to MariaDB. A `DatabaseMigrationStateStore` file records which database is active; it holds a hash of URL and user, never credentials.

```kotlin
val manager = DatabaseMigrationManager(DatabaseMigrationStateStore(dataFolder.resolve("storage-state.properties")))
manager.initializeActive(oldSettings) // once, for the database that is in use today

manager.prepare(newSettings, oldConnection = { oldDataSource.connection }, newConnection = { newDataSource.connection }, oldConfig = oldSettings)
```

- `prepare` does nothing when the new settings are the recorded database. Otherwise it creates every table with its columns and primary key in the new database and copies the rows in batches.
- The target must be empty. The copy runs in one transaction on the target and is committed only when the row counts of all tables match.
- While copying, the state is `IN_PROGRESS`. If anything fails, the old database stays the active one and `prepare` refuses to run again until the state file is fixed, so a half-filled target is never used by accident.
- Tables with auto-increment columns, foreign keys or secondary and unique indexes are rejected before anything is written. Column types are mapped through `DatabaseDialect` (`MySQLDialect`, `PostgreSQLDialect`, `SQLiteDialect`).

There is no ORM; map rows with `RowMapper`.
