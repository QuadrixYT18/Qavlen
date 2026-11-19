package de.quadrixyt.qavlen.commands

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.atomic.AtomicInteger

/**
 * Thread-safe per-source cooldowns. Expired entries are dropped when they are checked, by [purge], and
 * automatically every few thousand starts, so the map does not grow with the number of past users.
 */
public class Cooldowns @JvmOverloads constructor(private val clock: Clock = Clock.systemUTC()) {
    private val until = ConcurrentHashMap<String, Instant>()
    private val starts = AtomicInteger()

    /** Remaining time for [key], or [Duration.ZERO] when the cooldown is over. */
    public fun remaining(key: String): Duration {
        val end = until[key] ?: return Duration.ZERO
        val remaining = Duration.between(clock.instant(), end)
        if (remaining.isNegative || remaining.isZero) {
            until.remove(key, end)
            return Duration.ZERO
        }
        return remaining
    }

    public fun start(key: String, duration: Duration) {
        until[key] = clock.instant().plus(duration)
        purgeOccasionally()
    }

    /**
     * Starts the cooldown unless one is active, as a single atomic step.
     *
     * @return `true` when the cooldown was started, `false` when [key] is still cooling down
     */
    public fun tryStart(key: String, duration: Duration): Boolean = reserve(key, duration) != null

    /** Starts the cooldown if none is active and returns its end, which [release] needs to undo it. */
    internal fun reserve(key: String, duration: Duration): Instant? {
        val now = clock.instant()
        val end = now.plus(duration)
        var reserved = false
        until.compute(key) { _, current ->
            if (current != null && current > now) {
                current
            } else {
                reserved = true
                end
            }
        }
        if (!reserved) return null
        purgeOccasionally()
        return end
    }

    /** Undoes [reserve] unless the cooldown was replaced in the meantime. */
    internal fun release(key: String, end: Instant) {
        until.remove(key, end)
    }

    public fun reset(key: String) {
        until.remove(key)
    }

    private fun purgeOccasionally() {
        if (starts.incrementAndGet() % PURGE_INTERVAL == 0) purge()
    }

    /** Removes all expired entries. */
    public fun purge() {
        val now = clock.instant()
        until.entries.removeIf { it.value <= now }
    }

    private companion object {
        const val PURGE_INTERVAL = 4096
    }
}
