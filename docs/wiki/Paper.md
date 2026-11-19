`qavlen-paper` targets Paper 26.3 and is safe to use on Folia when you follow the threading notes below. Declare `paper-api` as `compileOnly`.

## Commands

```kotlin
override fun onEnable() {
    val translations = Translations.builder()
        .resources(javaClass.classLoader, "lang", listOf(Locale.ENGLISH, Locale.GERMAN))
        .directory(dataPath.resolve("lang"))
        .build()
    val commands = PaperCommands(this, CommandDispatcher(translations))
    commands.register(CoinsCommand())
}
```

`PaperCommands` registers commands through Paper's lifecycle API (`LifecycleEvents.COMMANDS`) as native Brigadier trees, so clients get syntax highlighting, inline validation and suggestions. Permissions are Brigadier requirements, which hides commands from players who cannot use them. `Player` arguments use Paper's player selector (`@p` works), `World` arguments the world argument; numbers, booleans and strings map to Brigadier types with their ranges.

`@Sender player: Player` receives the executing player (respecting `/execute as`), `@Sender sender: CommandSender` any sender. Messages render as MiniMessage in the player's client locale.

## Messages

Use `AdventureMessages` from `qavlen-adventure` (see [Language & Localization](Language-and-Localization)) for chat, titles and action bars. Placeholder values are inserted as text and cannot inject MiniMessage tags.

## Scheduling and Folia

`PaperScheduler(plugin)` exposes Paper's region-aware schedulers, which run on the correct thread on Paper and Folia:

| Method | Runs on | Use for |
| --- | --- | --- |
| `global`, `globalLater`, `globalTimer` | global region | world-independent state |
| `at`, `atLater`, `atTimer` | the region owning a location | blocks and chunks |
| `entity`, `entityLater`, `entityTimer` | the region owning an entity, following it | players and entities |
| `async`, `asyncLater`, `asyncTimer`, `supplyAsync` | a worker thread | I/O, databases, HTTP |

On Folia there is no main thread; never touch an entity or block from another region's thread. `PaperScheduler.isFolia` tells which server is running. `ticks(Duration)` converts durations to ticks.

## Menus

```kotlin
Menus.install(plugin) // once
val menu = Menu(Component.text("Shop"), rows = 3)
menu.set(13, ItemStack(Material.DIAMOND)) { click -> buy(click.player) }
menu.open(player)
```

Items cannot be moved in or out of menus unless `allowItemMovement` is enabled; shift-clicks and drags into the menu are cancelled as well. `PaginatedMenu` lays out a list across pages with navigation buttons that only appear when another page exists. `closeHandler` runs when a player closes the menu.

## Events

```kotlin
val subscription = Events.listen(plugin, PlayerJoinEvent::class.java) { event -> ... }
subscription.close() // unregisters
```

`plugin.listen<PlayerJoinEvent> { ... }` is the Kotlin shorthand. Priority and `ignoreCancelled` are optional parameters.

## Items

`ItemBuilder` creates item stacks through Paper's data component API. Names and lore are rendered without Minecraft's default italics, and both can come from translations so menus show the viewer's language. A multi-line translation becomes one lore line per line.

```kotlin
val locale = messages.locale(player)
val icon = ItemBuilder.of(Material.DIAMOND)
    .name(messages, locale, "shop.diamond.name")
    .lore(messages, locale, "shop.diamond.lore", placeholder("price", 250))
    .glint(true)
    .hide(DataComponentTypes.ENCHANTMENTS)
    .data(priceKey, 250)
    .build()

val head = ItemBuilder.of(Material.PLAYER_HEAD).profile(target.playerProfile).build()
val filler = ItemBuilder.of(Material.GRAY_STAINED_GLASS_PANE).hideTooltip().build()
```

Further methods: `amount`, `unbreakable`, `enchant` (also above the normal maximum level), `model` (item model from a resource pack) and `customModelData`. `ItemBuilder.from(item)` starts from a copy; `build()` returns a new stack each time.

In multi-line translations, close MiniMessage tags on the line that opens them, because lines are split before parsing.

## Plugin messaging

```kotlin
val channel = PluginChannel(plugin, "myplugin:sync") { player, data -> ... }
channel.send(player, bytes) // through this player's connection
channel.send(bytes)         // through any online player; false if nobody is online
channel.close()
```

This is the backend counterpart of the [Velocity](Velocity) `PluginChannel`. Plugin messages travel through a player's connection, so a player must be online. The backend cannot distinguish messages written by the proxy from messages written by the client. Only trust them when the proxy drops client messages on the channel, as Qavlen's Velocity `PluginChannel` does, and when the server is reachable only through the proxy. On Folia the handler runs on the connection's thread; use `PaperScheduler` before touching the world.

## Persistent data

```kotlin
val coins = PersistentKey(plugin, "coins", PersistentDataType.LONG)
coins.set(player, 10)
val balance = coins.getOrDefault(player, 0)
coins.set(itemStack, 5) // edits the item's container without copying its meta
```

## Safe teleports

`SafeTeleport.teleport(entity, location, radius)` loads the target chunk asynchronously, searches the nearest location with solid ground and two passable blocks without lava, fire, cactus or portals, and teleports with `teleportAsync`. It completes with `false` when nothing safe is found. The search runs on the thread owning the chunk and only inspects chunks owned by that thread.

## Packaging

Shade and relocate Qavlen, see [Installation](Installation#shading-plugins). The Kotlin example loads the Kotlin standard library through `libraries` in `plugin.yml`.
