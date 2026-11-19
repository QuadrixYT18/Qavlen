# JDA example (Kotlin)

A Discord bot with localized slash commands, a subcommand that requires the `MANAGE_MESSAGES` permission and a role option.

```shell
DISCORD_TOKEN=... DISCORD_GUILD=... ./gradlew :examples:jda-kotlin:run
```

With `DISCORD_GUILD` the commands are registered for that guild only, which updates instantly. Commands are uploaded only when they changed.
