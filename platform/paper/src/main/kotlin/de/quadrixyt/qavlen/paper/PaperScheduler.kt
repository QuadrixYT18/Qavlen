package de.quadrixyt.qavlen.paper

import io.papermc.paper.threadedregions.scheduler.ScheduledTask
import org.bukkit.Location
import org.bukkit.entity.Entity
import org.bukkit.plugin.Plugin
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.TimeUnit
import java.util.function.Supplier

/**
 * Schedules work on the thread that owns the data, using Paper's region-aware schedulers. The same code runs on
 * Paper, where every region scheduler targets the main thread, and on Folia, where each region ticks on its own
 * thread and there is no main thread.
 *
 * - [global] for world-independent state such as the weather or game rules.
 * - [at] for blocks and chunks around a location.
 * - [entity] for an entity; the task follows it across regions and is retired when the entity is removed.
 * - [async] for blocking I/O. Never touch world state from it.
 *
 * Tick-based delays use the server tick (50 ms); asynchronous delays use wall-clock time.
 */
public class PaperScheduler(private val plugin: Plugin) {
    private val server get() = plugin.server

    public fun global(task: Runnable): ScheduledTask = server.globalRegionScheduler.run(plugin) { task.run() }

    public fun globalLater(delayTicks: Long, task: Runnable): ScheduledTask =
        server.globalRegionScheduler.runDelayed(plugin, { task.run() }, delayTicks.coerceAtLeast(1))

    public fun globalTimer(delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        server.globalRegionScheduler.runAtFixedRate(plugin, { task.run() }, delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))

    public fun at(location: Location, task: Runnable): ScheduledTask = server.regionScheduler.run(plugin, location) { task.run() }

    public fun atLater(location: Location, delayTicks: Long, task: Runnable): ScheduledTask =
        server.regionScheduler.runDelayed(plugin, location, { task.run() }, delayTicks.coerceAtLeast(1))

    public fun atTimer(location: Location, delayTicks: Long, periodTicks: Long, task: Runnable): ScheduledTask =
        server.regionScheduler.runAtFixedRate(plugin, location, { task.run() }, delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))

    /**
     * Runs [task] on the entity's thread. Returns `null` when the entity was already removed; [retired] runs if the
     * entity is removed before the task executes.
     */
    @JvmOverloads
    public fun entity(entity: Entity, task: Runnable, retired: Runnable? = null): ScheduledTask? =
        entity.scheduler.run(plugin, { task.run() }, retired)

    @JvmOverloads
    public fun entityLater(entity: Entity, delayTicks: Long, task: Runnable, retired: Runnable? = null): ScheduledTask? =
        entity.scheduler.runDelayed(plugin, { task.run() }, retired, delayTicks.coerceAtLeast(1))

    @JvmOverloads
    public fun entityTimer(entity: Entity, delayTicks: Long, periodTicks: Long, task: Runnable, retired: Runnable? = null): ScheduledTask? =
        entity.scheduler.runAtFixedRate(plugin, { task.run() }, retired, delayTicks.coerceAtLeast(1), periodTicks.coerceAtLeast(1))

    public fun async(task: Runnable): ScheduledTask = server.asyncScheduler.runNow(plugin) { task.run() }

    public fun asyncLater(delay: Duration, task: Runnable): ScheduledTask =
        server.asyncScheduler.runDelayed(plugin, { task.run() }, delay.toMillis(), TimeUnit.MILLISECONDS)

    public fun asyncTimer(delay: Duration, period: Duration, task: Runnable): ScheduledTask =
        server.asyncScheduler.runAtFixedRate(plugin, {
            task.run()
        }, delay.toMillis(), period.toMillis().coerceAtLeast(1), TimeUnit.MILLISECONDS)

    /** Runs [supplier] asynchronously and completes the future with its result. */
    public fun <T> supplyAsync(supplier: Supplier<T>): CompletableFuture<T> {
        val future = CompletableFuture<T>()
        async {
            try {
                future.complete(supplier.get())
            } catch (exception: Throwable) {
                future.completeExceptionally(exception)
            }
        }
        return future
    }

    /** Cancels every task of this plugin. Paper also does this when the plugin is disabled. */
    public fun cancelAll() {
        server.globalRegionScheduler.cancelTasks(plugin)
        server.asyncScheduler.cancelTasks(plugin)
    }

    public companion object {
        /** `true` on Folia, where code must never assume a single main thread. */
        @JvmStatic
        public val isFolia: Boolean by lazy {
            runCatching { Class.forName("io.papermc.paper.threadedregions.RegionizedServer") }.isSuccess
        }

        /** Converts a duration to server ticks, rounding up to at least one tick. */
        @JvmStatic
        public fun ticks(duration: Duration): Long = ((duration.toMillis() + 49) / 50).coerceAtLeast(1)
    }
}
