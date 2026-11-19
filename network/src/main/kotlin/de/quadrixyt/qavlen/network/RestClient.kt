package de.quadrixyt.qavlen.network

import de.quadrixyt.qavlen.serialization.JsonCodec
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.net.URI
import java.net.URLEncoder
import java.net.http.HttpClient
import java.net.http.HttpHeaders
import java.net.http.HttpRequest
import java.net.http.HttpResponse
import java.net.http.HttpTimeoutException
import java.nio.ByteBuffer
import java.nio.charset.Charset
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.CompletionStage
import java.util.concurrent.Flow
import java.util.concurrent.TimeUnit
import kotlin.math.min

/** A response with a buffered text body. */
public class HttpResult internal constructor(
    public val status: Int,
    public val headers: HttpHeaders,
    public val body: String,
    private val codec: JsonCodec,
) {
    public val isSuccess: Boolean get() = status in 200..299

    public fun <T> json(type: Class<T>): T = codec.decode(body, type)

    /** @throws HttpStatusException when the status is not 2xx */
    public fun requireSuccess(): HttpResult = apply { if (!isSuccess) throw HttpStatusException(status, body) }
}

/** A request completed with a non-success status. The body is truncated in the message. */
public class HttpStatusException(public val status: Int, public val body: String) :
    RuntimeException("HTTP $status: ${body.take(200)}")

/**
 * Retries for transient failures.
 *
 * Only idempotent methods (`GET`, `HEAD`, `PUT`, `DELETE`, `OPTIONS`) are retried, after connection errors,
 * timeouts and the statuses in [statuses]. A `Retry-After` header in seconds is honored up to [maxDelay].
 */
public class RetryPolicy @JvmOverloads constructor(
    public val maxAttempts: Int = 3,
    public val initialDelay: Duration = Duration.ofMillis(250),
    public val maxDelay: Duration = Duration.ofSeconds(10),
    public val statuses: Set<Int> = setOf(429, 502, 503, 504),
) {
    init {
        require(maxAttempts in 1..10) { "maxAttempts must be between 1 and 10" }
    }

    internal fun delay(attempt: Int, retryAfter: Duration?): Duration {
        val exponential = initialDelay.multipliedBy(1L shl min(attempt - 1, 16))
        return minOf(retryAfter ?: exponential, maxDelay)
    }

    public companion object {
        @JvmField
        public val NONE: RetryPolicy = RetryPolicy(maxAttempts = 1)
    }
}

/**
 * A small HTTP client for JSON APIs on top of `java.net.http`.
 *
 * ```kotlin
 * val api = RestClient.builder("https://api.example.com/").header("Authorization", "Bearer $token").build()
 * val user = api.get("users/42").query("fields", "name").sendJson(User::class.java).join()
 * ```
 *
 * All requests are asynchronous. Every request has a timeout. Responses are buffered as text up to
 * [Builder.maxResponseSize]; larger responses fail with [HttpResponseTooLargeException].
 *
 * Close the client when it is no longer needed, for example when a plugin is disabled, to release its threads.
 */
public class RestClient private constructor(builder: Builder) : AutoCloseable {
    private val baseUri: URI = builder.baseUri
    private val headers: Map<String, String> = builder.headers.toMap()
    private val requestTimeout: Duration = builder.requestTimeout
    private val retry: RetryPolicy = builder.retry
    public val codec: JsonCodec = builder.codec
    private val maxResponseSize: Long = builder.maxResponseSize
    private val client: HttpClient = HttpClient.newBuilder()
        .connectTimeout(builder.connectTimeout)
        .followRedirects(HttpClient.Redirect.NORMAL)
        .build()

    public fun get(path: String): Request = Request("GET", path)

    public fun delete(path: String): Request = Request("DELETE", path)

    public fun post(path: String): Request = Request("POST", path)

    public fun put(path: String): Request = Request("PUT", path)

    public fun patch(path: String): Request = Request("PATCH", path)

    public fun request(method: String, path: String): Request = Request(method.uppercase(), path)

    /** Waits for running requests and releases the client's threads. */
    override fun close() {
        client.close()
    }

    /** A request being built. Instances are not thread-safe; [send] may be called more than once. */
    public inner class Request internal constructor(private val method: String, private val path: String) {
        private val query = mutableListOf<Pair<String, String>>()
        private val requestHeaders = linkedMapOf<String, String>()
        private var body: String? = null
        private var timeout: Duration = requestTimeout

        public fun query(name: String, value: Any): Request = apply { query += name to value.toString() }

        public fun header(name: String, value: String): Request = apply { requestHeaders[name] = value }

        public fun timeout(timeout: Duration): Request = apply { this.timeout = timeout }

        /** Sends [value] encoded as JSON. */
        public fun json(value: Any?): Request = apply {
            body = codec.encode(value)
            requestHeaders.putIfAbsent("Content-Type", "application/json")
        }

        public fun body(text: String, contentType: String): Request = apply {
            body = text
            requestHeaders["Content-Type"] = contentType
        }

        /** Completes with the response for any status; fails only for I/O errors and timeouts after retries. */
        public fun send(): CompletableFuture<HttpResult> = attempt(build(), 1)

        /** Completes with the decoded body of a 2xx response, or fails with [HttpStatusException]. */
        public fun <T> sendJson(type: Class<T>): CompletableFuture<T> = send().thenApply { it.requireSuccess().json(type) }

        private fun build(): HttpRequest {
            val uri = baseUri.resolve(path.removePrefix("/")).let { resolved ->
                if (query.isEmpty()) {
                    resolved
                } else {
                    val encoded = query.joinToString("&") { (name, value) -> "${encode(name)}=${encode(value)}" }
                    URI.create(resolved.toString() + (if (resolved.rawQuery == null) "?" else "&") + encoded)
                }
            }
            val publisher = body?.let(HttpRequest.BodyPublishers::ofString) ?: HttpRequest.BodyPublishers.noBody()
            val builder = HttpRequest.newBuilder(uri).timeout(timeout).method(method, publisher)
            (headers + requestHeaders).forEach { (name, value) -> builder.header(name, value) }
            builder.header("Accept", requestHeaders["Accept"] ?: "application/json")
            return builder.build()
        }

        private fun attempt(request: HttpRequest, attempt: Int): CompletableFuture<HttpResult> {
            val retryable = method in idempotent && attempt < retry.maxAttempts
            return client.sendAsync(request) { info -> LimitedText(maxResponseSize, charset(info.headers())) }
                .handle { response, failure ->
                    val cause = (failure as? CompletionException)?.cause ?: failure
                    when {
                        cause != null && retryable && (cause is IOException || cause is HttpTimeoutException) ->
                            later(retry.delay(attempt, null)) { attempt(request, attempt + 1) }
                        cause != null -> CompletableFuture.failedFuture(cause)
                        retryable && response.statusCode() in retry.statuses ->
                            later(retry.delay(attempt, retryAfter(response))) { attempt(request, attempt + 1) }
                        else -> CompletableFuture.completedFuture(
                            HttpResult(response.statusCode(), response.headers(), response.body(), codec),
                        )
                    }
                }
                .thenCompose { it }
        }

        private fun later(delay: Duration, next: () -> CompletableFuture<HttpResult>): CompletableFuture<HttpResult> =
            CompletableFuture.supplyAsync({ }, CompletableFuture.delayedExecutor(delay.toMillis(), TimeUnit.MILLISECONDS))
                .thenCompose { next() }
    }

    public class Builder internal constructor(internal val baseUri: URI) {
        internal val headers = linkedMapOf<String, String>()
        internal var connectTimeout: Duration = Duration.ofSeconds(5)
        internal var requestTimeout: Duration = Duration.ofSeconds(15)
        internal var retry: RetryPolicy = RetryPolicy()
        internal var codec: JsonCodec = JsonCodec.default
        internal var maxResponseSize: Long = 16L * 1024 * 1024

        /** Adds a header to every request, for example authorization. */
        public fun header(name: String, value: String): Builder = apply { headers[name] = value }

        public fun connectTimeout(timeout: Duration): Builder = apply { connectTimeout = timeout }

        public fun requestTimeout(timeout: Duration): Builder = apply { requestTimeout = timeout }

        public fun retry(policy: RetryPolicy): Builder = apply { retry = policy }

        public fun codec(codec: JsonCodec): Builder = apply { this.codec = codec }

        /** Largest response body that is read, in bytes. Defaults to 16 MiB. */
        public fun maxResponseSize(bytes: Long): Builder = apply {
            require(bytes > 0) { "maxResponseSize must be positive" }
            maxResponseSize = bytes
        }

        public fun build(): RestClient = RestClient(this)
    }

    public companion object {
        private val idempotent = setOf("GET", "HEAD", "PUT", "DELETE", "OPTIONS")

        /** [baseUri] is the prefix for relative request paths; end it with `/` to keep its last segment. */
        @JvmStatic
        public fun builder(baseUri: String): Builder {
            val uri = URI.create(baseUri)
            require(uri.scheme == "http" || uri.scheme == "https") { "Only http and https URIs are supported" }
            return Builder(uri)
        }

        private fun encode(value: String): String = URLEncoder.encode(value, Charsets.UTF_8).replace("+", "%20")

        private fun charset(headers: HttpHeaders): Charset = headers.firstValue("Content-Type")
            .map { type -> type.split(';').map(String::trim).firstOrNull { it.startsWith("charset=", ignoreCase = true) } }
            .map { it?.substringAfter('=')?.trim('"') }
            .map { name -> runCatching { name?.let(Charset::forName) }.getOrNull() }
            .orElse(null) ?: Charsets.UTF_8

        private fun retryAfter(response: HttpResponse<*>): Duration? =
            response.headers().firstValue("Retry-After").map { it.trim().toLongOrNull() }.orElse(null)
                ?.takeIf { it >= 0 }?.let(Duration::ofSeconds)
    }
}

/** A response body exceeded [RestClient.Builder.maxResponseSize]. */
public class HttpResponseTooLargeException(public val limit: Long) : RuntimeException("Response body exceeds $limit bytes")

/** Collects the body as text and cancels the download once it exceeds [limit]. */
private class LimitedText(private val limit: Long, private val charset: Charset) : HttpResponse.BodySubscriber<String> {
    private val result = CompletableFuture<String>()
    private val bytes = ByteArrayOutputStream()
    private lateinit var subscription: Flow.Subscription

    override fun getBody(): CompletionStage<String> = result

    override fun onSubscribe(subscription: Flow.Subscription) {
        this.subscription = subscription
        subscription.request(Long.MAX_VALUE)
    }

    override fun onNext(item: List<ByteBuffer>) {
        if (result.isDone) return
        for (buffer in item) {
            if (bytes.size() + buffer.remaining() > limit) {
                subscription.cancel()
                result.completeExceptionally(HttpResponseTooLargeException(limit))
                return
            }
            val chunk = ByteArray(buffer.remaining())
            buffer.get(chunk)
            bytes.write(chunk)
        }
    }

    override fun onError(throwable: Throwable) {
        result.completeExceptionally(throwable)
    }

    override fun onComplete() {
        result.complete(bytes.toString(charset))
    }
}
