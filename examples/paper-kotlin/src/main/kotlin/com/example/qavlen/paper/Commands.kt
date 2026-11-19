package com.example.qavlen.paper

import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.commands.annotations.Command
import de.quadrixyt.qavlen.commands.annotations.Cooldown
import de.quadrixyt.qavlen.commands.annotations.Execute
import de.quadrixyt.qavlen.commands.annotations.Permission
import de.quadrixyt.qavlen.commands.annotations.Range
import de.quadrixyt.qavlen.commands.annotations.Sender
import de.quadrixyt.qavlen.commands.annotations.Subcommand
import de.quadrixyt.qavlen.language.placeholder
import de.quadrixyt.qavlen.paper.PaginatedMenu
import de.quadrixyt.qavlen.paper.PaperScheduler
import de.quadrixyt.qavlen.paper.PersistentKey
import de.quadrixyt.qavlen.paper.SafeTeleport
import net.kyori.adventure.text.Component
import org.bukkit.Material
import org.bukkit.entity.Player
import org.bukkit.inventory.ItemStack

@Command("coins", description = "coins.description")
class CoinsCommand(
    private val coins: PersistentKey<Long, Long>,
    private val scheduler: PaperScheduler,
    private val messages: AdventureMessages,
    private val startingBalance: Long,
) {
    @Execute
    fun balance(@Sender player: Player) {
        messages.send(player, "coins.balance", placeholder("amount", coins.getOrDefault(player, startingBalance)))
    }

    @Subcommand("give")
    @Permission("example.coins.give")
    fun give(@Sender sender: Player, target: Player, @Range(min = 1.0, max = 1_000_000.0) amount: Int) {
        // Entity data must be changed on the entity's own thread, which matters on Folia.
        scheduler.entity(target, {
            coins.set(target, coins.getOrDefault(target, startingBalance) + amount)
            messages.send(target, "coins.received", placeholder("amount", amount))
        })
        messages.send(sender, "coins.given", placeholder("amount", amount), placeholder("player", target.name))
    }
}

@Command("players")
class PlayersCommand(private val messages: AdventureMessages) {
    @Execute
    fun open(@Sender player: Player) {
        val locale = messages.locale(player)
        val menu = PaginatedMenu(
            messages.render(locale, "players.title"),
            rows = 6,
            contentSlots = (0 until 45).toList(),
            items = player.server.onlinePlayers.toList(),
            render = { online ->
                ItemStack(Material.PLAYER_HEAD).apply {
                    editMeta { it.displayName(messages.render(locale, "players.entry", placeholder("player", online.name))) }
                }
            },
            action = { click, online -> click.player.sendMessage(Component.text(online.name)) },
        )
        menu.navigation(45, ItemStack(Material.ARROW), 53, ItemStack(Material.ARROW))
        menu.open(player)
    }
}

@Command("spawn")
class SpawnCommand(private val scheduler: PaperScheduler, private val messages: AdventureMessages) {
    @Execute
    @Cooldown(10)
    fun spawn(@Sender player: Player) {
        SafeTeleport.teleport(player, player.world.spawnLocation).thenAccept { success ->
            scheduler.entity(player, { messages.send(player, if (success) "spawn.teleported" else "spawn.failed") })
        }
    }
}
