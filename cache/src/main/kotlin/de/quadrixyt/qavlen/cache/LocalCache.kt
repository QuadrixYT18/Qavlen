package de.quadrixyt.qavlen.cache

import com.github.benmanes.caffeine.cache.AsyncCache
import com.github.benmanes.caffeine.cache.Caffeine
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.function.Function

/**
 * A thread-safe in-memory cache backed by Caffeine, with size and time based eviction.
 *
 * ```kotlin
 * val profiles = LocalCache.builder().maximumSize(10_000).expireAfterWrite(Duration.ofMinutes(5)).build<UUID, Profile>()
 * ```
 */
public class LocalCache<K : Any, V : Any> private constructor(private val cache: AsyncCache<K, V>) : Cache<K, V> {
    private val synchronous = cache.synchronous()

    override fun get(key: K): V? = synchronous.getIfPresent(key)

    override fun get(key: K, loader: Function<in K, out V?>): V? = synchronous.get(key) { loader.apply(it) }

    override fun getAsync(key: K, loader: Function<in K, out CompletableFuture<out V?>>): CompletableFuture<V?> =
        cache.get(key) { requested, _ ->
            @Suppress("UNCHECKED_CAST")
            loader.apply(requested) as CompletableFuture<V>
        }.thenApply { it }

    override fun put(key: K, value: V) {
        synchronous.put(key, value)
    }

    override fun invalidate(key: K) {
        synchronous.invalidate(key)
    }

    override fun invalidateAll() {
        synchronous.invalidateAll()
    }

    /** Approximate number of entries. */
    public fun size(): Long = synchronous.estimatedSize()

    /** Removes expired entries now instead of during later operations. */
    public fun cleanUp() {
        synchronous.cleanUp()
    }

    override fun stats(): CacheStats = synchronous.stats().let { CacheStats(it.hitCount(), it.missCount(), it.evictionCount()) }

    public class Builder internal constructor() {
        private val caffeine = Caffeine.newBuilder().recordStats()

        public fun maximumSize(size: Long): Builder = apply { caffeine.maximumSize(size) }

        public fun expireAfterWrite(duration: Duration): Builder = apply { caffeine.expireAfterWrite(duration) }

        public fun expireAfterAccess(duration: Duration): Builder = apply { caffeine.expireAfterAccess(duration) }

        public fun <K : Any, V : Any> build(): LocalCache<K, V> = LocalCache(caffeine.buildAsync())
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()
    }
}
