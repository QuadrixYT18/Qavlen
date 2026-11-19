# Velocity example (Kotlin)

A proxy plugin with a `/hub <server>` command that uses a typed `RegisteredServer` argument, and a plugin messaging channel that only accepts messages from backend servers.

```shell
./gradlew :examples:velocity-kotlin:shadowJar
```

The plugin descriptor is written by hand in `velocity-plugin.json` because Velocity's annotation processor does not run on Kotlin sources.
