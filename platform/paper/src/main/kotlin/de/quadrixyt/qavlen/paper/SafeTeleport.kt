package de.quadrixyt.qavlen.paper

import org.bukkit.Bukkit
import org.bukkit.Location
import org.bukkit.Material
import org.bukkit.Tag
import org.bukkit.block.Block
import org.bukkit.entity.Entity
import org.bukkit.event.player.PlayerTeleportEvent
import java.util.concurrent.CompletableFuture

/**
 * Finds a location where an entity can stand without suffocating, falling into the void or landing in lava, and
 * teleports there.
 *
 * Chunks are loaded asynchronously and the search runs on the region thread that owns the target, so this is safe
 * on Paper and Folia and never blocks the caller.
 */
public object SafeTeleport {
    private val dangerous = setOf(
        Material.LAVA, Material.FIRE, Material.SOUL_FIRE, Material.MAGMA_BLOCK, Material.CACTUS,
        Material.CAMPFIRE, Material.SOUL_CAMPFIRE, Material.SWEET_BERRY_BUSH, Material.POWDER_SNOW, Material.WITHER_ROSE,
    )

    /**
     * Teleports [entity] to the nearest safe location within [radius] blocks of [target], searching the target column
     * first. Completes with `false` when no safe location exists or the teleport was cancelled.
     */
    @JvmStatic
    @JvmOverloads
    public fun teleport(entity: Entity, target: Location, radius: Int = 3): CompletableFuture<Boolean> =
        find(target, radius).thenCompose { safe ->
            if (safe ==
                null
            ) {
                CompletableFuture.completedFuture(false)
            } else {
                entity.teleportAsync(safe, PlayerTeleportEvent.TeleportCause.PLUGIN)
            }
        }

    /** Completes with a safe location near [target], or `null`. Must not be joined on a server thread. */
    @JvmStatic
    @JvmOverloads
    public fun find(target: Location, radius: Int = 3): CompletableFuture<Location?> {
        require(radius in 0..16) { "radius must be between 0 and 16" }
        val world = requireNotNull(target.world) { "Location has no world" }
        return world.getChunkAtAsync(target).thenApply { search(target, radius) }
    }

    /** `true` when an entity can stand with its feet in [feet]. Call only on the thread owning the block. */
    @JvmStatic
    public fun isSafe(feet: Block): Boolean {
        val ground = feet.getRelative(0, -1, 0)
        val head = feet.getRelative(0, 1, 0)
        return ground.type.isSolid &&
            !ground.isLiquid &&
            ground.type !in dangerous &&
            passable(feet) &&
            passable(head)
    }

    private fun passable(block: Block): Boolean =
        block.isPassable && !block.isLiquid && block.type !in dangerous && !Tag.PORTALS.isTagged(block.type)

    private fun search(target: Location, radius: Int): Location? {
        val world = target.world ?: return null
        val minY = world.minHeight + 1
        val maxY = world.maxHeight - 2
        val offsets = (-radius..radius).flatMap { x -> (-radius..radius).map { z -> x to z } }
            .sortedBy { (x, z) -> x * x + z * z }
        for ((dx, dz) in offsets) {
            val x = target.blockX + dx
            val z = target.blockZ + dz
            // Only loaded chunks owned by this thread are inspected; on Folia neighbours may belong to another region.
            if (!world.isChunkLoaded(x shr 4, z shr 4) || !Bukkit.isOwnedByCurrentRegion(world, x shr 4, z shr 4)) continue
            val startY = target.blockY.coerceIn(minY, maxY)
            val candidates = (0..maxY - minY).asSequence().flatMap { step -> sequenceOf(startY + step, startY - step - 1) }
                .filter { it in minY..maxY }
            val y = candidates.firstOrNull { isSafe(world.getBlockAt(x, it, z)) } ?: continue
            return Location(world, x + 0.5, y.toDouble(), z + 0.5, target.yaw, target.pitch)
        }
        return null
    }
}
