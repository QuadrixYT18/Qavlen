package de.quadrixyt.qavlen.cache

import java.util.concurrent.CompletableFuture
import java.util.function.Function

/** Hit and miss counts since the cache was created. */
public data class CacheStats(val hits: Long, val misses: Long, val evictions: Long) {
    val requests: Long get() = hits + misses

    val hitRate: Double get() = if (requests == 0L) 1.0 else hits.toDouble() / requests
}

/**
 * A key-value cache. [LocalCache] keeps entries in memory; `qavlen-cache-redis` provides a shared implementation
 * with the same interface, so code can switch between them without changes.
 */
public interface Cache<K : Any, V : Any> {
    public fun get(key: K): V?

    /** Returns the cached value or computes, stores and returns it. A `null` result is not stored. */
    public fun get(key: K, loader: Function<in K, out V?>): V?

    /** Like [get] with a loader, but loads asynchronously. Concurrent calls for the same key share one load. */
    public fun getAsync(key: K, loader: Function<in K, out CompletableFuture<out V?>>): CompletableFuture<V?>

    public fun put(key: K, value: V)

    public fun invalidate(key: K)

    public fun invalidateAll()

    public fun stats(): CacheStats
}
