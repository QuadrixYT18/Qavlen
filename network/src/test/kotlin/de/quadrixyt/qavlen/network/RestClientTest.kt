package de.quadrixyt.qavlen.network

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.time.Duration
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicInteger
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertIs

class RestClientTest {
    data class Greeting(val text: String, val count: Int)

    private val calls = AtomicInteger()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        createContext("/greet") { exchange ->
            val body = """{"text":"hello ${exchange.requestURI.query}","count":${calls.incrementAndGet()}}""".toByteArray()
            exchange.responseHeaders.add("Content-Type", "application/json")
            exchange.sendResponseHeaders(200, body.size.toLong())
            exchange.responseBody.use { it.write(body) }
        }
        createContext("/flaky") { exchange ->
            val status = if (calls.incrementAndGet() < 3) 503 else 200
            exchange.responseHeaders.add("Retry-After", "0")
            exchange.sendResponseHeaders(status, -1)
            exchange.close()
        }
        createContext("/echo") { exchange ->
            val body = exchange.requestBody.readAllBytes()
            calls.incrementAndGet()
            exchange.sendResponseHeaders(if (exchange.requestMethod == "POST") 503 else 200, body.size.toLong().takeIf { it > 0 } ?: -1)
            exchange.responseBody.use { it.write(body) }
        }
        start()
    }
    private val client = RestClient.builder("http://127.0.0.1:${server.address.port}/")
        .retry(RetryPolicy(maxAttempts = 3, initialDelay = Duration.ofMillis(10)))
        .build()

    @AfterTest
    fun stop() = server.stop(0)

    @Test
    fun `decodes json with encoded query parameters`() {
        val greeting = client.get("greet").query("name", "A & B").sendJson(Greeting::class.java).join()

        assertEquals(Greeting("hello name=A & B", 1), greeting)
    }

    @Test
    fun `retries idempotent requests on transient statuses`() {
        val result = client.get("flaky").send().join()

        assertEquals(200, result.status)
        assertEquals(3, calls.get())
    }

    @Test
    fun `does not retry post requests`() {
        val result = client.post("echo").json(Greeting("x", 1)).send().join()

        assertEquals(503, result.status)
        assertEquals(1, calls.get())
        val failure = assertFailsWith<CompletionException> { client.post("echo").json(1).sendJson(Int::class.java).join() }
        assertIs<HttpStatusException>(failure.cause)
    }
}
