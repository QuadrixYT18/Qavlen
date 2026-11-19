What Qavlen is built against and how each combination was verified. "Live" means the example plugins ran on a real server; "CI" means automated tests run on every push.

| Component | Version | Verification |
| --- | --- | --- |
| Java | 25 (required) | CI with Temurin 25 |
| Kotlin | 2.4.21 | CI; public API is checked for binary compatibility |
| KSP | 2.3.14 | CI with real Kotlin compilations |
| Gradle | 9.9.1 (wrapper) | CI |
| Paper | 26.3 (build 256) | Live: commands, translated errors, player selector, items, plugin channel registration, `/minecraft:reload` |
| Folia | 26.3 | Uses only Paper's region-aware APIs; not run on a Folia server yet |
| Velocity | 4.3.1 (build 45) | Live: commands, server arguments, usage messages |
| JDA | 6.7.0 | CI: slash command mapping, localizations and option constraints; not run against Discord yet |
| Adventure / MiniMessage | 5.2.0 | CI; provided by Paper and Velocity at runtime |
| Brigadier | 1.3.11 (Paper), Velocity's fork | CI against both |
| Redis | 8 | CI against a Redis service |
| HikariCP | 7.1.0 | CI with H2 in PostgreSQL mode |
| MariaDB / PostgreSQL | drivers 3.5 / 42.7 | Connection helpers only; not tested against real servers yet |
| Jackson | 3.3.0 | CI |
| Caffeine | 3.3.1 | CI |
| SnakeYAML / Gson | 2.7 / 2.15.0 | CI |
| Jackson TOML | 3.3.0 | CI |

Java and Kotlin are equally supported: `tests/java-api` uses the public API from plain Java, and every example exists in both languages.
