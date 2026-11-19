`qavlen-jda` turns Qavlen commands into Discord slash commands with JDA 6.

```kotlin
val commands = JdaCommands(CommandDispatcher(translations))
commands.register(PollCommand())
val jda = JDABuilder.createLight(token).addEventListeners(commands).build().awaitReady()
commands.syncGlobal(jda).join()          // or syncGuild(guild) while developing
```

```java
JdaCommands commands = new JdaCommands(new CommandDispatcher(translations));
commands.register(new PollCommand());
JDA jda = JDABuilder.createLight(token).addEventListeners(commands).build().awaitReady();
commands.syncGlobal(jda).join();
```

## Structure

A root command either has options or subcommands. Subcommands may contain one further level, which becomes a Discord subcommand group. A node with subcommands cannot be executable itself, and Discord has no aliases; `register` rejects structures Discord cannot express.

## Options

| Qavlen type | Discord option |
| --- | --- |
| `Int`, `Long` | integer with min/max (limited to ±2^53) |
| `Double` | number with min/max |
| `Boolean` | boolean |
| `String` (including greedy) | string |
| enums | string with choices (autocomplete above 25 constants) |
| `User`, `Member`, `Role`, `GuildChannel`, `Message.Attachment` | user, role, channel, attachment |
| other types | string with autocomplete from `ArgumentType.suggest` |

## Localization

Descriptions of commands, subcommands and arguments are translation keys or literal text. The default locale's text is the description; every other locale that Discord supports is sent as a description localization. Replies use the user's Discord locale.

## Permissions

Permission strings are names of JDA `Permission` constants, for example `@Permission("MANAGE_MESSAGES")`. A root permission also becomes the command's default member permission, so Discord hides it from members without it. Supply your own `DiscordPermissions` to map other permission strings, for example to roles.

## Responses and deadlines

Executors run on virtual threads, not on JDA's event thread. Discord requires a response within three seconds: if an executor has not replied after `deferAfter` (two seconds by default), the interaction is deferred and later replies are sent as follow-ups. Replies are ephemeral unless `ephemeralReplies` is `false`; errors are always ephemeral. An executor that never replies is acknowledged silently. `context.sender(SlashCommandInteractionEvent::class.java)` gives full access to the event, for example to send embeds or modals.

## Registration and rate limits

`syncGlobal` and `syncGuild` fetch the registered commands and compare them with the local definitions. An update is only sent when something changed, so restarting a bot does not consume Discord's daily command creation limit. Global commands may take a while to appear in clients; guild commands update immediately.

Buttons, select menus and modals are not wrapped; use JDA's listeners for them.
