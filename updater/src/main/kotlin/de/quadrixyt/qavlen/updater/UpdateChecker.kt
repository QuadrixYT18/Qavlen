package de.quadrixyt.qavlen.updater

import java.net.URI
import java.time.Clock
import java.time.Instant
import java.util.concurrent.CompletableFuture
import java.util.concurrent.CompletionException
import java.util.concurrent.atomic.AtomicReference

/**
 * Checks whether a newer version of your plugin or application is published on Modrinth, GitHub Releases or Hangar.
 *
 * ```kotlin
 * val checker = UpdateChecker.modrinth("my-plugin", pluginMeta.version) { platform("paper"); minecraftVersion("26.3") }
 * checker.checkAsync().thenAccept { result ->
 *     if (result is UpdateResult.Available) logger.info("Update available: ${result.latestVersion} ${result.releaseUrl}")
 * }
 * ```
 *
 * Checks run asynchronously on the HTTP client's threads and never block the caller; callbacks run on those threads,
 * so switch to the server thread before touching game objects. A successful result is reused for
 * [UpdateOptions.cacheDuration], and concurrent checks share one request. Failures are not cached.
 *
 * Nothing is downloaded or installed: results only contain links. Close the checker when it is no longer needed,
 * for example when the plugin is disabled.
 */
public class UpdateChecker internal constructor(
    private val source: ReleaseSource,
    /** The installed version, compared with the published ones. */
    public val currentVersion: String,
    public val options: UpdateOptions,
    endpoint: URI,
    private val clock: Clock = Clock.systemUTC(),
) : AutoCloseable {
    private val http = UpdateHttp(
        endpoint,
        options.userAgent ?: "Qavlen-Updater (+https://github.com/QuadrixYT18/Qavlen) ${source.description}",
        options.timeout,
    )
    private val running = AtomicReference<CompletableFuture<UpdateResult>?>()

    private class Cached(val result: UpdateResult, val until: Instant)

    @Volatile
    private var cached: Cached? = null

    /** The result of the last completed check, or `null` before the first one. */
    @Volatile
    public var lastResult: UpdateResult? = null
        private set

    /**
     * Checks for an update. Completes with a cached result when one is still valid, joins a check that is already
     * running, and otherwise asks the provider. The future never completes exceptionally: problems are
     * [UpdateResult.Failed]. Cancelling it only stops waiting for this caller.
     */
    public fun checkAsync(): CompletableFuture<UpdateResult> {
        cached?.takeIf { clock.instant().isBefore(it.until) }?.let { return CompletableFuture.completedFuture(it.result) }
        while (true) {
            running.get()?.let { return it.copy() }
            val check = CompletableFuture<UpdateResult>()
            if (running.compareAndSet(null, check)) {
                start(check)
                return check.copy()
            }
        }
    }

    /** Like [checkAsync], but waits for the result. Never call it on a server's main thread. */
    public fun check(): UpdateResult = checkAsync().join()

    /** Forgets the cached result, so the next check asks the provider again. */
    public fun invalidate() {
        cached = null
    }

    private fun start(check: CompletableFuture<UpdateResult>) {
        val result = try {
            source.releases(http, options)
        } catch (exception: RuntimeException) {
            CompletableFuture.failedFuture(exception)
        }
        result.handle { releases, failure -> if (failure == null) evaluate(releases) else failed(failure) }
            .whenComplete { outcome, _ ->
                if (outcome !is UpdateResult.Failed) cached = Cached(outcome, clock.instant().plus(options.cacheDuration))
                lastResult = outcome
                running.set(null)
                check.complete(outcome)
            }
    }

    private fun evaluate(releases: List<Release>): UpdateResult {
        val suitable = releases.filter { options.channel.includes(channelOf(it)) }
        val latest = newest(suitable) ?: return UpdateResult.UpToDate(currentVersion, null)
        if (normalize(latest.version) == normalize(currentVersion)) return UpdateResult.UpToDate(currentVersion, latest.version)
        val compared = SemanticVersion.compare(latest.version, currentVersion) ?: return UpdateResult.Failed(
            currentVersion,
            "Cannot compare the installed version '$currentVersion' with the published version '${latest.version}' " +
                "of ${source.description}",
        )
        return if (compared > 0) {
            UpdateResult.Available(
                currentVersion,
                latest.version,
                channelOf(latest),
                latest.releaseUrl,
                latest.downloadUrl,
                latest.notes,
                latest.publishedAt,
            )
        } else {
            UpdateResult.UpToDate(currentVersion, latest.version)
        }
    }

    private fun failed(failure: Throwable): UpdateResult.Failed {
        val cause = (failure as? CompletionException)?.cause ?: failure
        return if (cause is UpdateCheckException) {
            UpdateResult.Failed(currentVersion, "${source.description}: ${cause.reason}", cause.cause, cause.retryAfter)
        } else {
            UpdateResult.Failed(currentVersion, "${source.description}: unexpected response (${cause.javaClass.simpleName})", cause)
        }
    }

    /** Releases a provider marks as stable but whose version names a pre-release count as that pre-release. */
    private fun channelOf(release: Release): UpdateChannel {
        val named = SemanticVersion.parse(release.version)?.channel ?: UpdateChannel.STABLE
        return maxOf(release.channel, named)
    }

    /** The highest comparable version; when none is comparable, the most recently published one. */
    private fun newest(releases: List<Release>): Release? {
        val comparable = releases.mapNotNull { release -> SemanticVersion.parse(release.version)?.let { release to it } }
        return comparable.maxWithOrNull { a, b -> a.second.compareTo(b.second) }?.first ?: releases.firstOrNull()
    }

    private fun normalize(version: String) = version.trim().removePrefix("v").removePrefix("V")

    /** Releases the HTTP client. A running check may still complete. */
    override fun close(): Unit = http.close()

    override fun toString(): String = "UpdateChecker(${source.description}, current=$currentVersion)"

    public companion object {
        private val projectName = Regex("[A-Za-z0-9_.-]+")

        /**
         * Checks a Modrinth project, given by its slug (`my-plugin`) or id (`AABBCCDD`). Versions marked `beta` or
         * `alpha` on Modrinth only count in those [UpdateOptions.channel]s.
         */
        @JvmStatic
        @JvmOverloads
        public fun modrinth(projectId: String, currentVersion: String, options: UpdateOptions = UpdateOptions.defaults()): UpdateChecker {
            require(projectName.matches(projectId)) { "Invalid Modrinth project '$projectId'" }
            return UpdateChecker(ModrinthSource(projectId), currentVersion, options, MODRINTH)
        }

        /** Kotlin form: `UpdateChecker.modrinth("my-plugin", "1.0.0") { platform("paper") }`. */
        @JvmSynthetic
        public fun modrinth(projectId: String, currentVersion: String, configure: UpdateOptions.Builder.() -> Unit): UpdateChecker =
            modrinth(projectId, currentVersion, UpdateOptions.builder().apply(configure).build())

        /**
         * Checks the releases of a GitHub repository, given as `owner/repo`. The release tag is the version, for
         * example `v1.2.0`; drafts are ignored and pre-releases count as betas. Pass a token with
         * [UpdateOptions.Builder.githubToken] for private repositories or more than 60 checks per hour and address.
         */
        @JvmStatic
        @JvmOverloads
        public fun github(repository: String, currentVersion: String, options: UpdateOptions = UpdateOptions.defaults()): UpdateChecker {
            require(repositoryName.matches(repository)) { "Invalid GitHub repository '$repository'; use owner/repo" }
            return UpdateChecker(GitHubSource(repository), currentVersion, options, GITHUB)
        }

        /** Kotlin form of [github] with options. */
        @JvmSynthetic
        public fun github(repository: String, currentVersion: String, configure: UpdateOptions.Builder.() -> Unit): UpdateChecker =
            github(repository, currentVersion, UpdateOptions.builder().apply(configure).build())

        private val repositoryName = Regex("[A-Za-z0-9-]+/[A-Za-z0-9_.-]+")
        internal val MODRINTH: URI = URI.create("https://api.modrinth.com/")
        internal val GITHUB: URI = URI.create("https://api.github.com/")
    }
}
