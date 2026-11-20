package de.quadrixyt.qavlen.updater

import java.net.URI
import java.time.Instant

/** The outcome of an update check. Failures are never reported as [UpToDate]. */
public sealed interface UpdateResult {
    /** The installed version the check compared against. */
    public val currentVersion: String

    /**
     * A newer version is published in the configured channel and fits the configured platform and Minecraft version.
     *
     * @property releaseUrl the page that describes the release, for people to open
     * @property downloadUrl the file to download, or `null` when the provider has no direct file for it
     * @property releaseNotes the release notes as published, for example Markdown. They come from a third party:
     *   do not parse them as MiniMessage or HTML.
     */
    public data class Available(
        override val currentVersion: String,
        val latestVersion: String,
        val channel: UpdateChannel,
        val releaseUrl: URI,
        val downloadUrl: URI?,
        val releaseNotes: String?,
        val publishedAt: Instant?,
    ) : UpdateResult

    /**
     * No newer version is published. [latestVersion] is the newest suitable version, which may also be older than
     * the installed one, or `null` when no published version fits the configured channel, platform and Minecraft version.
     */
    public data class UpToDate(
        override val currentVersion: String,
        val latestVersion: String?,
    ) : UpdateResult

    /**
     * The check did not complete or its result is uncertain, for example after a network error, a rate limit, an
     * unknown project or versions that cannot be compared.
     *
     * @property retryAfter when the provider asked to wait before the next request, or `null`
     */
    public data class Failed(
        override val currentVersion: String,
        val reason: String,
        val cause: Throwable? = null,
        val retryAfter: Instant? = null,
    ) : UpdateResult
}
