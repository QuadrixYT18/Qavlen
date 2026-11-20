package de.quadrixyt.qavlen.updater

import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZoneOffset
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class CachingTest {
    private val now = Instant.parse("2026-11-20T12:00:00Z")

    /** A clock tests can move forward. */
    private class MutableClock(var instant: Instant) : Clock() {
        override fun getZone() = ZoneOffset.UTC

        override fun withZone(zone: java.time.ZoneId?) = this

        override fun instant() = instant
    }

    private val releases = """[{"tag_name":"v2.0.0","draft":false,"prerelease":false,"html_url":"https://github.com/o/p/releases/tag/v2.0.0","assets":[]}]"""

    private fun checker(server: TestServer, clock: Clock, cache: Duration = Duration.ZERO) = UpdateChecker(
        GitHubSource("o/p"),
        "1.0.0",
        UpdateOptions.builder().cacheDuration(cache).build(),
        server.uri,
        clock,
    )

    @Test
    fun `results are reused for the cache duration`() {
        TestServer { TestResponse(body = releases) }.use { server ->
            val clock = MutableClock(now)
            checker(server, clock, Duration.ofMinutes(30)).use { checker ->
                checker.check()
                clock.instant = now.plus(Duration.ofMinutes(29))
                checker.check()
                assertEquals(1, server.requests.size)

                clock.instant = now.plus(Duration.ofMinutes(31))
                checker.check()
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun `unchanged responses are revalidated with the ETag`() {
        TestServer { request ->
            if (request.headers["if-none-match"] == "\"v1\"") TestResponse(304) else TestResponse(body = releases, headers = mapOf("ETag" to "\"v1\""))
        }.use { server ->
            checker(server, MutableClock(now)).use { checker ->
                val first = checker.check()
                val second = checker.check()

                assertIs<UpdateResult.Available>(second)
                assertEquals(first, second)
                assertNull(server.requests[0].headers["if-none-match"])
                assertEquals("\"v1\"", server.requests[1].headers["if-none-match"])

                checker.invalidate()
                checker.check()
                assertNull(server.requests[2].headers["if-none-match"])
            }
        }
    }

    @Test
    fun `a 429 with Retry-After pauses requests until it ends`() {
        TestServer { TestResponse(429, headers = mapOf("Retry-After" to "120")) }.use { server ->
            val clock = MutableClock(now)
            checker(server, clock).use { checker ->
                val limited = checker.check()

                assertIs<UpdateResult.Failed>(limited)
                assertEquals(now.plusSeconds(120), limited.retryAfter)
                assertTrue("rate limit" in limited.reason, limited.reason)
                assertEquals(limited, checker.check())
                assertEquals(1, server.requests.size)

                clock.instant = now.plusSeconds(121)
                checker.check()
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun `rate limit headers of Modrinth and GitHub are understood`() {
        TestServer { TestResponse(403, headers = mapOf("X-RateLimit-Remaining" to "0", "X-RateLimit-Reset" to now.plusSeconds(600).epochSecond.toString())) }
            .use { server ->
                val result = checker(server, MutableClock(now)).use { it.check() }
                assertEquals(now.plusSeconds(600), (result as UpdateResult.Failed).retryAfter)
            }
        TestServer { TestResponse(429, headers = mapOf("X-RateLimit-Reset" to "45")) }.use { server ->
            val result = checker(server, MutableClock(now)).use { it.check() }
            assertEquals(now.plusSeconds(45), (result as UpdateResult.Failed).retryAfter)
        }
        TestServer { TestResponse(403) }.use { server ->
            val result = checker(server, MutableClock(now)).use { it.check() }
            assertNull((result as UpdateResult.Failed).retryAfter)
        }
    }

    @Test
    fun `server errors are retried once and never cached`() {
        var calls = 0
        TestServer { if (++calls == 1) TestResponse(503) else TestResponse(body = releases) }.use { server ->
            assertIs<UpdateResult.Available>(checker(server, MutableClock(now)).use { it.check() })
            assertEquals(2, server.requests.size)
        }
    }
}
