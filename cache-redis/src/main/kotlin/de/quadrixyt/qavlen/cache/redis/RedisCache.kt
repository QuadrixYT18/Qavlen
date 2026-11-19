package de.quadrixyt.qavlen.cache.redis

import de.quadrixyt.qavlen.cache.Cache
import de.quadrixyt.qavlen.cache.CacheStats
import de.quadrixyt.qavlen.serialization.JsonCodec
import redis.clients.jedis.UnifiedJedis
import redis.clients.jedis.params.ScanParams
import redis.clients.jedis.params.SetParams
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.Executor
import java.util.concurrent.Executors
import java.util.concurrent.atomic.AtomicLong
import java.util.function.Function

/**
 * A cache shared through Redis. Values are stored as JSON under `<namespace>:<key>`, optionally with a TTL.
 *
 * Redis calls block, so [getAsync] runs them on virtual threads. Concurrent asynchronous loads of the same key in this
 * process share one load; loads in other processes are not coordinated. The client is owned by the caller.
 */
public class RedisCache<K : Any, V : Any> private constructor(builder: Builder<K, V>) : Cache<K, V> {
    private val redis = builder.redis
    private val namespace = builder.namespace
    private val valueType = builder.valueType
    private val ttl = builder.ttl
    private val codec = builder.codec
    private val keyText = builder.keyText
    private val inFlight = ConcurrentHashMap<K, CompletableFuture<V?>>()
    private val hits = AtomicLong()
    private val misses = AtomicLong()

    override fun get(key: K): V? {
        val value = redis.get(redisKey(key))?.let { codec.decode(it, valueType) }
        (if (value == null) misses else hits).incrementAndGet()
        return value
    }

    override fun get(key: K, loader: Function<in K, out V?>): V? = get(key) ?: loader.apply(key)?.also { put(key, it) }

    override fun getAsync(key: K, loader: Function<in K, out CompletableFuture<out V?>>): CompletableFuture<V?> {
        val created = CompletableFuture<V?>()
        val existing = inFlight.putIfAbsent(key, created)
        if (existing != null) return existing
        CompletableFuture.supplyAsync({ get(key) }, blockingExecutor)
            .thenCompose { cached ->
                if (cached != null) {
                    CompletableFuture.completedFuture(cached)
                } else {
                    loader.apply(key).thenApplyAsync({ loaded -> loaded?.also { put(key, it) } }, blockingExecutor)
                }
            }
            .whenComplete { value, failure ->
                inFlight.remove(key, created)
                if (failure != null) created.completeExceptionally(failure) else created.complete(value)
            }
        return created
    }

    override fun put(key: K, value: V) {
        val json = codec.encode(value)
        if (ttl ==
            null
        ) {
            redis.set(redisKey(key), json)
        } else {
            redis.set(redisKey(key), json, SetParams.setParams().ex(ttl.toSeconds().coerceAtLeast(1)))
        }
    }

    override fun invalidate(key: K) {
        redis.del(redisKey(key))
    }

    /** Deletes every key of this namespace using `SCAN`, which does not block Redis like `KEYS` would. */
    override fun invalidateAll() {
        val params = ScanParams().match(escape(namespace) + ":*").count(500)
        var cursor = ScanParams.SCAN_POINTER_START
        do {
            val page = redis.scan(cursor, params)
            if (page.result.isNotEmpty()) redis.del(*page.result.toTypedArray())
            cursor = page.cursor
        } while (cursor != ScanParams.SCAN_POINTER_START)
    }

    /** Hits and misses of this instance. Evictions happen inside Redis and are not counted. */
    override fun stats(): CacheStats = CacheStats(hits.get(), misses.get(), 0)

    private fun redisKey(key: K): String = "$namespace:${keyText.apply(key)}"

    public class Builder<K : Any, V : Any> internal constructor(
        internal val redis: UnifiedJedis,
        internal val namespace: String,
        internal val valueType: Class<V>,
    ) {
        internal var ttl: Duration? = null
        internal var codec: JsonCodec = JsonCodec.default
        internal var keyText: Function<K, String> = Function { it.toString() }

        /** Entries expire after [ttl], rounded up to whole seconds. */
        public fun expireAfterWrite(ttl: Duration): Builder<K, V> = apply {
            require(!ttl.isNegative && !ttl.isZero) { "TTL must be positive" }
            this.ttl = ttl
        }

        public fun codec(codec: JsonCodec): Builder<K, V> = apply { this.codec = codec }

        /** How keys are written into Redis keys. Defaults to `toString()`. */
        public fun keyText(keyText: Function<K, String>): Builder<K, V> = apply { this.keyText = keyText }

        public fun build(): RedisCache<K, V> = RedisCache(this)
    }

    public companion object {
        private val blockingExecutor: Executor = Executors.newThreadPerTaskExecutor(Thread.ofVirtual().name("qavlen-redis-", 0).factory())

        /**
         * @param redis for example `RedisClient.create("redis://localhost:6379")`
         * @param namespace prefix that separates this cache from other data in the same Redis database
         */
        @JvmStatic
        public fun <K : Any, V : Any> builder(redis: UnifiedJedis, namespace: String, valueType: Class<V>): Builder<K, V> {
            require(namespace.isNotBlank() && ':' !in namespace) { "Namespace must be non-blank and must not contain ':'" }
            return Builder(redis, namespace, valueType)
        }

        private fun escape(pattern: String): String = pattern.replace(Regex("[*?\\[\\]\\\\]")) { "\\" + it.value }
    }
}
