package de.quadrixyt.qavlen.scheduler

import java.time.Duration
import java.util.concurrent.Callable
import java.util.concurrent.CompletableFuture
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import java.util.concurrent.ScheduledFuture
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import java.util.function.Consumer

/** A scheduled task. Cancelling a running repetition lets it finish but prevents further runs. */
public class ScheduledTask internal constructor(private val future: ScheduledFuture<*>) {
    public val isCancelled: Boolean get() = future.isCancelled

    public val isDone: Boolean get() = future.isDone

    /** Remaining time until the next run, or zero when it is due or done. */
    public val delay: Duration get() = Duration.ofNanos(future.getDelay(TimeUnit.NANOSECONDS).coerceAtLeast(0))

    public fun cancel(): Boolean = future.cancel(false)
}

/**
 * Runs delayed and repeating tasks on a small, named thread pool for plain JVM applications and bots.
 *
 * Unlike a bare `ScheduledExecutorService`, an exception in a repeating task is reported to the error handler and
 * the task keeps running instead of being silently cancelled. Game servers should use their platform scheduler,
 * for example `PaperScheduler`, for anything that touches game state.
 */
public class TaskScheduler @JvmOverloads constructor(
    name: String = "qavlen-scheduler",
    threads: Int = 1,
    private val errorHandler: Consumer<Throwable> = Consumer { error ->
        System.getLogger(TaskScheduler::class.java.name).log(System.Logger.Level.ERROR, "Scheduled task failed", error)
    },
) : AutoCloseable {
    private val ids = AtomicInteger()
    private val executor: ScheduledExecutorService = Executors.newScheduledThreadPool(threads) { runnable ->
        Thread(runnable, "$name-${ids.incrementAndGet()}").apply { isDaemon = true }
    }

    init {
        require(threads in 1..64) { "threads must be between 1 and 64" }
    }

    public fun runLater(delay: Duration, task: Runnable): ScheduledTask =
        ScheduledTask(executor.schedule(guarded(task), delay.toNanos(), TimeUnit.NANOSECONDS))

    /** Runs at a fixed rate. Runs never overlap; a late run is followed by the next one immediately. */
    public fun runRepeating(initialDelay: Duration, period: Duration, task: Runnable): ScheduledTask {
        require(!period.isNegative && !period.isZero) { "period must be positive" }
        return ScheduledTask(executor.scheduleAtFixedRate(guarded(task), initialDelay.toNanos(), period.toNanos(), TimeUnit.NANOSECONDS))
    }

    /** Runs with a fixed pause between the end of one run and the start of the next. */
    public fun runWithFixedDelay(initialDelay: Duration, delay: Duration, task: Runnable): ScheduledTask {
        require(!delay.isNegative && !delay.isZero) { "delay must be positive" }
        return ScheduledTask(executor.scheduleWithFixedDelay(guarded(task), initialDelay.toNanos(), delay.toNanos(), TimeUnit.NANOSECONDS))
    }

    /** Runs [task] as soon as possible and completes with its result. */
    public fun <T> submit(task: Callable<T>): CompletableFuture<T> {
        val result = CompletableFuture<T>()
        executor.execute {
            try {
                result.complete(task.call())
            } catch (exception: Exception) {
                result.completeExceptionally(exception)
            }
        }
        return result
    }

    /** Stops accepting tasks, waits up to [timeout] for running tasks and interrupts what is left. */
    @JvmOverloads
    public fun shutdown(timeout: Duration = Duration.ofSeconds(5)) {
        executor.shutdown()
        if (!executor.awaitTermination(timeout.toMillis(), TimeUnit.MILLISECONDS)) executor.shutdownNow()
    }

    override fun close(): Unit = shutdown()

    private fun guarded(task: Runnable) = Runnable {
        try {
            task.run()
        } catch (exception: Exception) {
            errorHandler.accept(exception)
        }
    }
}
