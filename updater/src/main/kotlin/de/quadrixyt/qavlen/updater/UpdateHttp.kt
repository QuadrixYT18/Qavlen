package de.quadrixyt.qavlen.updater

import de.quadrixyt.qavlen.network.HttpResponseTooLargeException
import de.quadrixyt.qavlen.network.HttpResult
import de.quadrixyt.qavlen.network.RestClient
import de.quadrixyt.qavlen.network.RetryPolicy
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Clock
import java.time.Duration
import java.time.Instant
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.ConcurrentHashMap

/**
 * Requests to one provider. Redirects are only followed within the provider's host, responses are limited in size,
 * and failures become [UpdateCheckException]s with a reason that never contains request headers or tokens.
 *
 * Responses with an `ETag` are remembered and requested again with `If-None-Match`, so an unchanged list costs the
 * provider no work; a `304 Not Modified` reuses the remembered body. Rate limits (`429`, or `403` without remaining
 * requests on GitHub) fail with the time the provider asked to wait.
 */
internal class UpdateHttp(
    private val base: URI,
    userAgent: String,
    timeout: Duration,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private class Tagged(val etag: String, val json: Any?)

    private val tagged = ConcurrentHashMap<String, Tagged>()

    private val client = RestClient.builder(base.toString())
        .followRedirects(false)
        .connectTimeout(minOf(timeout, Duration.ofSeconds(5)))
        .requestTimeout(timeout)
        .maxResponseSize(MAX_RESPONSE_SIZE)
        // Only short retries for server hiccups; rate limits are reported to the caller instead.
        .retry(RetryPolicy(maxAttempts = 2, statuses = setOf(502, 503, 504)))
        .header("User-Agent", userAgent)
        .build()

    /** GETs [path] relative to the provider's base URI and returns the decoded JSON body. */
    fun getJson(
        path: String,
        query: List<Pair<String, String>> = emptyList(),
        headers: Map<String, String> = emptyMap(),
    ): CompletableFuture<Any?> {
        val key = path + query.joinToString("&", "?") { (name, value) -> "$name=$value" }
        val remembered = tagged[key]
        val request = client.get(path)
        query.forEach { (name, value) -> request.query(name, value) }
        headers.forEach { (name, value) -> request.header(name, value) }
        remembered?.let { request.header("If-None-Match", it.etag) }
        return request.send().handle { result, failure ->
            if (failure == null && result?.status == NOT_MODIFIED && remembered != null) {
                CompletableFuture.completedFuture(remembered.json)
            } else {
                respond(result, failure, redirects = 0, headers, key)
            }
        }.thenCompose { it }
    }

    /** Forgets remembered responses, so the next requests ask for full content. */
    fun forget() = tagged.clear()

    /** Encodes [values] as a JSON array of strings, the format Modrinth expects in query parameters. */
    fun jsonArray(values: List<String>): String = client.codec.encode(values)

    private fun respond(
        result: HttpResult?,
        failure: Throwable?,
        redirects: Int,
        headers: Map<String, String>,
        key: String? = null,
    ): CompletableFuture<Any?> {
        if (failure != null) return CompletableFuture.failedFuture(translate(failure))
        val response = checkNotNull(result)
        return when (response.status) {
            in 200..299 -> CompletableFuture.completedFuture(decode(response).also { json -> remember(key, response, json) })
            301, 302, 303, 307, 308 -> follow(response, redirects, headers)
            else -> CompletableFuture.failedFuture(statusFailure(response))
        }
    }

    private fun remember(key: String?, response: HttpResult, json: Any?) {
        val etag = response.headers.firstValue("ETag").orElse(null)
        if (key != null && etag != null) tagged[key] = Tagged(etag, json)
    }

    private fun follow(response: HttpResult, redirects: Int, headers: Map<String, String>): CompletableFuture<Any?> {
        val location = response.headers.firstValue("Location").orElse(null)
            ?.let { runCatching { base.resolve(it) }.getOrNull() }
        if (location == null || !sameOrigin(location) || redirects >= MAX_REDIRECTS) {
            return CompletableFuture.failedFuture(UpdateCheckException("${base.host} redirected to an unexpected location"))
        }
        val request = client.get(location.toString())
        headers.forEach { (name, value) -> request.header(name, value) }
        return request.send().handle { result, failure -> respond(result, failure, redirects + 1, headers) }.thenCompose { it }
    }

    private fun sameOrigin(location: URI): Boolean =
        location.host == base.host && location.scheme == base.scheme && location.port == base.port

    private fun decode(response: HttpResult): Any? = try {
        client.codec.decode(response.body, Any::class.java)
    } catch (exception: RuntimeException) {
        throw UpdateCheckException("${base.host} sent a response that is not valid JSON", exception)
    }

    private fun statusFailure(response: HttpResult): UpdateCheckException {
        val exhausted = response.headers.firstValue("X-RateLimit-Remaining").orElse(null) == "0"
        return when {
            response.status == TOO_MANY_REQUESTS || response.status == FORBIDDEN && exhausted -> {
                val until = retryAfter(response)
                UpdateCheckException("${base.host} rate limit reached; next check allowed at $until", retryAfter = until)
            }
            response.status == NOT_FOUND -> UpdateCheckException("Not found on ${base.host}; check the project name")
            response.status == UNAUTHORIZED -> UpdateCheckException("${base.host} rejected the credentials")
            else -> UpdateCheckException("${base.host} answered with HTTP ${response.status}")
        }
    }

    /**
     * When the provider allows the next request: `Retry-After` in seconds or as an HTTP date, else
     * `X-RateLimit-Reset`, which Modrinth sends in seconds from now and GitHub as a Unix time, else one minute.
     * Waits are capped at one day.
     */
    private fun retryAfter(response: HttpResult): Instant {
        val now = clock.instant()
        val latest = now.plusSeconds(MAX_WAIT_SECONDS)
        val header = response.headers.firstValue("Retry-After").orElse(null)?.trim()
        header?.toLongOrNull()?.let { return now.plusSeconds(it.coerceIn(0, MAX_WAIT_SECONDS)) }
        header?.let { text -> runCatching { ZonedDateTime.parse(text, DateTimeFormatter.RFC_1123_DATE_TIME).toInstant() }.getOrNull() }
            ?.let { return minOf(it, latest) }
        val reset = response.headers.firstValue("X-RateLimit-Reset").orElse(null)?.trim()?.toLongOrNull()
        return when {
            reset == null -> now.plusSeconds(DEFAULT_WAIT_SECONDS)
            reset > UNIX_TIME_THRESHOLD -> minOf(Instant.ofEpochSecond(reset), latest)
            else -> now.plusSeconds(reset.coerceIn(1, MAX_WAIT_SECONDS))
        }
    }

    private fun translate(failure: Throwable): UpdateCheckException {
        val cause = (failure as? CompletionException)?.cause ?: failure
        return when (cause) {
            is UpdateCheckException -> cause
            is HttpTimeoutException -> UpdateCheckException("${base.host} did not answer in time", cause)
            is HttpResponseTooLargeException -> UpdateCheckException("${base.host} sent a response larger than ${cause.limit} bytes", cause)
            is IOException -> UpdateCheckException("Could not reach ${base.host}: ${cause.message ?: cause.javaClass.simpleName}", cause)
            else -> UpdateCheckException("Update check against ${base.host} failed: ${cause.javaClass.simpleName}", cause)
        }
    }

    override fun close() = client.close()

    private companion object {
        const val MAX_RESPONSE_SIZE = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 3
        const val NOT_MODIFIED = 304
        const val UNAUTHORIZED = 401
        const val FORBIDDEN = 403
        const val NOT_FOUND = 404
        const val TOO_MANY_REQUESTS = 429
        const val DEFAULT_WAIT_SECONDS = 60L
        const val MAX_WAIT_SECONDS = 24L * 60 * 60

        /** Reset values above this are Unix times (2001), below it seconds from now. */
        const val UNIX_TIME_THRESHOLD = 1_000_000_000L
    }
}
