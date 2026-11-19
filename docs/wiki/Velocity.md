`qavlen-velocity` targets the Velocity API 4.3. Declare `velocity-api` as `compileOnly`.

```kotlin
class MyPlugin @Inject constructor(private val server: ProxyServer) {
    @Subscribe
    fun onInitialize(event: ProxyInitializeEvent) {
        val commands = VelocityCommands(server, this, CommandDispatcher(translations))
        commands.register(HubCommand(commands.messages))
    }
}
```

Commands are registered as Brigadier commands with their aliases. Arguments of type `Player` (connected players) and `RegisteredServer` (backend servers) are available. Velocity runs commands on its own thread pool, so executors must be thread-safe; blocking briefly is acceptable, long-running work should use Velocity's scheduler or asynchronous APIs. Messages render as MiniMessage in the player's locale.

Server switching uses Velocity's API directly:

```kotlin
player.createConnectionRequest(target).connect().thenAccept { result -> ... }
```

## Plugin messaging

```kotlin
val channel = PluginChannel(server, this, MinecraftChannelIdentifier.create("myplugin", "main")) { connection, data ->
    // a message from the backend server behind `connection`
}
channel.send(player, bytes)   // to the server the player is on
channel.send(registeredServer, bytes)
channel.close()
```

Messages on the channel are always marked as handled and never forwarded. Only messages from backend servers reach the handler; messages sent by clients are dropped, because any client can send arbitrary data on any channel.

The Kotlin example writes `velocity-plugin.json` by hand because Velocity's annotation processor does not see Kotlin sources; Java plugins can use `annotationProcessor("com.velocitypowered:velocity-api:...")`.
