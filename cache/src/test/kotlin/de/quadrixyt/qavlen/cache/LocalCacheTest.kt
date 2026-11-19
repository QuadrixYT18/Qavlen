package de.quadrixyt.qavlen.cache

import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull

class LocalCacheTest {
    @Test
    fun `loads once, counts hits and invalidates`() {
        val cache = LocalCache.builder().maximumSize(100).build<String, Int>()
        val loads = AtomicInteger()

        assertEquals(
            3,
            cache.get("abc") {
                loads.incrementAndGet()
                it.length
            },
        )
        assertEquals(
            3,
            cache.get("abc") {
                loads.incrementAndGet()
                it.length
            },
        )
        assertNull(cache.get("missing") { null })
        cache.invalidate("abc")
        assertNull(cache.get("abc"))

        assertEquals(1, loads.get())
        assertEquals(1, cache.stats().hits)
    }

    @Test
    fun `async loads are shared and failures are not cached`() {
        val cache = LocalCache.builder().expireAfterWrite(Duration.ofMinutes(1)).build<Int, String>()
        val pending = CompletableFuture<String?>()
        val loads = AtomicInteger()

        val first = cache.getAsync(1) {
            loads.incrementAndGet()
            pending
        }
        val second = cache.getAsync(1) {
            loads.incrementAndGet()
            CompletableFuture.completedFuture("other")
        }
        pending.complete("one")

        assertEquals("one", first.join())
        assertEquals("one", second.join())
        assertEquals(1, loads.get())
        assertEquals("one", cache.get(1))
    }

    @Test
    fun `evicts beyond the maximum size`() {
        val cache = LocalCache.builder().maximumSize(10).build<Int, Int>()
        repeat(100) { cache.put(it, it) }
        cache.cleanUp()

        assertEquals(10, cache.size())
    }
}
