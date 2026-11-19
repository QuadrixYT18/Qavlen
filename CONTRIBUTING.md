# Contributing

Keep changes focused and preserve the separation between platform-neutral modules and platform adapters.

Before opening a pull request:

- Use JDK 25 and the Gradle Wrapper.
- Run `./gradlew clean build`.
- Add tests for behavior changes and update documentation for public API changes.
- Keep public APIs Java-friendly and document them with KDoc.
- Add a module only when it contains working functionality.

Use English Conventional Commit messages, for example `feat(config): add schema migration support`.
