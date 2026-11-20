package de.quadrixyt.qavlen.updater

import com.sun.net.httpserver.HttpServer
import java.net.InetSocketAddress
import java.net.URI
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/** A local HTTP server that answers every request with [respond], so tests never depend on the real providers. */
class TestServer(private val respond: (TestRequest) -> TestResponse) : AutoCloseable {
    val requests = CopyOnWriteArrayList<TestRequest>()
    private val server = HttpServer.create(InetSocketAddress("127.0.0.1", 0), 0).apply {
        executor = Executors.newCachedThreadPool()
        createContext("/") { exchange ->
            val request = TestRequest(exchange.requestURI, exchange.requestHeaders.mapKeys { it.key.lowercase() }.mapValues { it.value.first() })
            requests += request
            val response = respond(request)
            response.headers.forEach { (name, value) -> exchange.responseHeaders.add(name, value) }
            Thread.sleep(response.delayMillis)
            val bytes = response.body.toByteArray()
            exchange.sendResponseHeaders(response.status, if (bytes.isEmpty()) -1 else bytes.size.toLong())
            if (bytes.isNotEmpty()) exchange.responseBody.use { it.write(bytes) }
            exchange.close()
        }
        start()
    }

    val uri: URI get() = URI.create("http://127.0.0.1:${server.address.port}/")

    override fun close() {
        server.stop(0)
        (server.executor as java.util.concurrent.ExecutorService).shutdownNow()
    }
}

data class TestRequest(val uri: URI, val headers: Map<String, String>) {
    /** Query parameters, decoded. */
    val query: Map<String, String>
        get() = uri.query.orEmpty().split('&').filter { it.isNotEmpty() }.associate {
            it.substringBefore('=') to java.net.URLDecoder.decode(it.substringAfter('='), Charsets.UTF_8)
        }
}

data class TestResponse(val status: Int = 200, val body: String = "", val headers: Map<String, String> = emptyMap(), val delayMillis: Long = 0)
