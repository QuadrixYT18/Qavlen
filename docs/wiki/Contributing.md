# Contributing

The repository's [CONTRIBUTING.md](https://github.com/QuadrixYT18/Qavlen/blob/master/CONTRIBUTING.md) describes the workflow. In short:

- Build with JDK 25 and `./gradlew build`, which compiles all modules and examples and runs tests, ktlint and detekt.
- Every behaviour change needs a test; Redis tests run when `REDIS_URL` is set.
- Public APIs need KDoc and must be usable from Java.
- Documentation lives in `docs/wiki` and is synchronized to this wiki on every push to `master`.
