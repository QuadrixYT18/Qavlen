# Troubleshooting

**`No generated command descriptor ..._QavlenCommand`**
The annotation processor did not run. Add `qavlen-commands-processor` with `ksp(...)` (Kotlin, requires the KSP plugin) or `annotationProcessor(...)` (Java). Shading must keep the generated classes; do not minimize them away.

**`No argument type registered for ...`**
A parameter type has no `ArgumentType`. Platform types are registered when you create `PaperCommands`, `VelocityCommands` or `JdaCommands`; create the adapter before registering commands. Register custom types with `dispatcher.argumentTypes.register(...)`.

**`Key conflict at '...'` when loading a configuration**
The file contains a value where the defaults define a section, or the other way round. Qavlen refuses to overwrite it; fix the file or add a migration.

**Translations show the key instead of text**
The key is missing for the locale and all fallbacks. Check the file name (`de.yml`, `de_DE.yml`), the namespace (`lang/<namespace>/<locale>.yml` is addressed as `namespace:key`) and run `translations.validate()`.

**`Blocking database call on Server thread`**
A synchronous `Database` method ran while `forbidBlockingWhen` returned `true`. Use the `Async` variants and switch back to the right thread with `PaperScheduler`.

**Slash commands do not appear**
Global commands can take time to reach clients. Use `syncGuild` during development. `register` throws if a structure cannot be expressed as a slash command.

**`ClassNotFoundException` for Kotlin classes on Paper**
Either list `kotlin-stdlib` under `libraries` in `plugin.yml` or bundle it in the shadow jar.

**Build fails with "Unsupported class file major version"**
Qavlen targets Java 25. Use a JDK 25 toolchain.
