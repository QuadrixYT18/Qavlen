package de.quadrixyt.qavlen.serialization

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

data class Counter(@Range(min = 0.0) val count: Int = 0, val label: String = "start")

class TypedConfigConcurrencyTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `concurrent updates and saves lose nothing`() {
        val path = directory.resolve("counter.yml")
        val config = Configs.open<Counter>(path)
        val failures = CopyOnWriteArrayList<Throwable>()
        val pool = Executors.newFixedThreadPool(8)
        val start = CountDownLatch(1)

        repeat(8) {
            pool.execute {
                start.await()
                repeat(250) {
                    try {
                        config.update { copy(count = count + 1) }
                        if (it % 25 == 0) config.save()
                        config.encode()
                    } catch (exception: Throwable) {
                        failures += exception
                    }
                }
            }
        }
        start.countDown()
        pool.shutdown()
        assertTrue(pool.awaitTermination(30, TimeUnit.SECONDS))

        assertEquals(emptyList(), failures)
        assertEquals(2000, config.value.count)
        config.save()
        assertEquals(2000, Configs.open<Counter>(path).value.count)
    }

    @Test
    fun `readers always see a complete value during reloads`() {
        val path = directory.resolve("counter.yml")
        val config = Configs.open<Counter>(path)
        val running = AtomicBoolean(true)
        val failures = CopyOnWriteArrayList<Throwable>()
        val readers = (1..4).map {
            Thread {
                while (running.get()) {
                    val value = config.value
                    if (value.count !in listOf(0, 1, 2) || value.label.isEmpty()) failures += IllegalStateException("Saw $value")
                }
            }.apply { start() }
        }

        repeat(200) { round ->
            path.writeText("count: ${round % 3}\nlabel: round$round\n")
            try {
                config.reload()
            } catch (exception: Exception) {
                failures += exception
            }
        }
        running.set(false)
        readers.forEach(Thread::join)

        assertEquals(emptyList(), failures)
        assertEquals(Counter(1, "round199"), config.value)
    }
}
