package de.quadrixyt.qavlen.scheduler

import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TaskSchedulerTest {
    @Test
    fun `repeating tasks survive exceptions and stop when cancelled`() {
        val errors = AtomicInteger()
        TaskScheduler(errorHandler = { errors.incrementAndGet() }).use { scheduler ->
            val runs = AtomicInteger()
            val fiveRuns = CountDownLatch(5)
            val task = scheduler.runRepeating(Duration.ZERO, Duration.ofMillis(5)) {
                runs.incrementAndGet()
                fiveRuns.countDown()
                error("boom")
            }

            assertTrue(fiveRuns.await(5, TimeUnit.SECONDS))
            task.cancel()
            val afterCancel = runs.get()
            Thread.sleep(50)

            assertTrue(task.isCancelled)
            assertTrue(runs.get() <= afterCancel + 1)
            assertTrue(errors.get() >= 5)
        }
    }

    @Test
    fun `delayed and submitted tasks complete`() {
        TaskScheduler(threads = 2).use { scheduler ->
            val latch = CountDownLatch(1)
            scheduler.runLater(Duration.ofMillis(10)) { latch.countDown() }

            assertEquals(42, scheduler.submit { 42 }.get(5, TimeUnit.SECONDS))
            assertTrue(latch.await(5, TimeUnit.SECONDS))
        }
    }
}
