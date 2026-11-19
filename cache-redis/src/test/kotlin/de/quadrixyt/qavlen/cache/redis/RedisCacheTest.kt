package de.quadrixyt.qavlen.cache.redis

import de.quadrixyt.qavlen.cache.CacheStats
import org.junit.jupiter.api.Assumptions.assumeTrue
import redis.clients.jedis.RedisClient
import java.time.Duration
import java.util.UUID
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** Runs against a real Redis server when `REDIS_URL` is set, as in CI. */
class RedisCacheTest {
    data class Profile(val name: String, val coins: Int)

    private fun client(): RedisClient {
        val url = System.getenv("REDIS_URL")
        assumeTrue(url != null, "REDIS_URL is not set")
        return RedisClient.create(url)
    }

    @Test
    fun `stores json values with ttl and invalidates the namespace`() {
        client().use { redis ->
            val namespace = "qavlen-test-${UUID.randomUUID()}"
            val cache = RedisCache.builder<String, Profile>(redis, namespace, Profile::class.java).expireAfterWrite(Duration.ofMinutes(1)).build()

            cache.put("alex", Profile("Alex", 3))
            assertEquals(Profile("Alex", 3), cache.get("alex"))
            assertEquals(Profile("Sam", 1), cache.getAsync("sam") { CompletableFuture.completedFuture(Profile("Sam", 1)) }.join())
            assertTrue(redis.ttl("$namespace:alex") in 1..60)

            cache.invalidateAll()
            assertNull(cache.get("alex"))
            assertNull(cache.get("sam"))
            assertEquals(CacheStats(hits = 1, misses = 3, evictions = 0), cache.stats())
        }
    }
}
