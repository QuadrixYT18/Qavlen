# Contributing

Thank you for helping. Please open an issue before larger changes so the design can be discussed first.

## Setup

- JDK 25. Gradle is provided by the wrapper.
- `./gradlew build` compiles every module and example and runs tests, ktlint and detekt.
- `./gradlew ktlintFormat` fixes formatting.
- Redis tests run when `REDIS_URL` points to a Redis server, for example `redis://localhost:6379`.

## Guidelines

- Keep general modules independent of Paper, Velocity and JDA. Platform modules must not depend on each other.
- Add an abstraction only when it has a concrete use.
- Public APIs need KDoc and must work from Java: no required Kotlin function types, `Pair` or `Unit`, and overloads for optional parameters.
- Every behaviour change needs a test. Avoid tests that only exercise mocks.
- Update `docs/wiki` and `CHANGELOG.md` for user-visible changes.

## Commits

Use English [Conventional Commits](https://www.conventionalcommits.org), for example:

```text
feat(config): support TOML files
fix(jda): keep option order when syncing
docs(paper): explain menu pagination
```
