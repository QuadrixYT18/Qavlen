package de.quadrixyt.qavlen.commands

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.util.concurrent.ConcurrentHashMap

/** Thread-safe per-source cooldowns. Expired entries are dropped lazily and by [purge]. */
public class Cooldowns @JvmOverloads constructor(private val clock: Clock = Clock.systemUTC()) {
    private val until = ConcurrentHashMap<String, Instant>()

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
    }

    public fun reset(key: String) {
        until.remove(key)
    }

    /** Removes all expired entries. */
    public fun purge() {
        val now = clock.instant()
        until.entries.removeIf { it.value <= now }
    }
}
