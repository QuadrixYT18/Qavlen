package de.quadrixyt.qavlen.updater

import de.quadrixyt.qavlen.network.HttpResponseTooLargeException
import de.quadrixyt.qavlen.network.HttpResult
import de.quadrixyt.qavlen.network.RestClient
import de.quadrixyt.qavlen.network.RetryPolicy
import java.io.IOException
import java.net.URI
import java.net.http.HttpTimeoutException
import java.time.Duration
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException

/**
 * Requests to one provider. Redirects are only followed within the provider's host, responses are limited in size,
 * and failures become [UpdateCheckException]s with a reason that never contains request headers or tokens.
 */
internal class UpdateHttp(private val base: URI, userAgent: String, timeout: Duration) : AutoCloseable {
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
        val request = client.get(path)
        query.forEach { (name, value) -> request.query(name, value) }
        headers.forEach { (name, value) -> request.header(name, value) }
        return request.send().handle { result, failure -> respond(result, failure, redirects = 0, headers) }.thenCompose { it }
    }

    private fun respond(result: HttpResult?, failure: Throwable?, redirects: Int, headers: Map<String, String>): CompletableFuture<Any?> {
        if (failure != null) return CompletableFuture.failedFuture(translate(failure))
        val response = checkNotNull(result)
        return when (response.status) {
            in 200..299 -> CompletableFuture.completedFuture(decode(response))
            301, 302, 303, 307, 308 -> follow(response, redirects, headers)
            else -> CompletableFuture.failedFuture(statusFailure(response))
        }
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

    private fun statusFailure(response: HttpResult): UpdateCheckException = when (response.status) {
        404 -> UpdateCheckException("Not found on ${base.host}; check the project name")
        401 -> UpdateCheckException("${base.host} rejected the credentials")
        else -> UpdateCheckException("${base.host} answered with HTTP ${response.status}")
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

    /** Encodes [values] as a JSON array of strings, the format Modrinth expects in query parameters. */
    fun jsonArray(values: List<String>): String = client.codec.encode(values)

    override fun close() = client.close()

    private companion object {
        const val MAX_RESPONSE_SIZE = 8L * 1024 * 1024
        const val MAX_REDIRECTS = 3
    }
}
