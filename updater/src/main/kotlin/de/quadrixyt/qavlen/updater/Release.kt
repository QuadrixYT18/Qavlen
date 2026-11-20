package de.quadrixyt.qavlen.updater

import java.net.URI
import java.time.Instant
import java.util.concurrent.CompletableFuture

/** A published version that fits the configured platform and Minecraft version. */
internal class Release(
    val version: String,
    val channel: UpdateChannel,
    val releaseUrl: URI,
    val downloadUrl: URI?,
    val notes: String?,
    val publishedAt: Instant?,
)

/** The provider's channel, or the channel the version names when that is less stable, as in `2.0.0-beta.1`. */
internal val Release.effectiveChannel: UpdateChannel
    get() = maxOf(channel, SemanticVersion.parse(version)?.channel ?: UpdateChannel.STABLE)

/** A provider: lists the releases of one project, newest first, without the channel filter. */
internal interface ReleaseSource {
    /** For messages, for example `Modrinth project 'luckperms'`. */
    val description: String

    fun releases(http: UpdateHttp, options: UpdateOptions): CompletableFuture<List<Release>>
}

/** An update check failed for [reason]; [retryAfter] is set when the provider asked to wait. */
internal class UpdateCheckException(
    val reason: String,
    cause: Throwable? = null,
    val retryAfter: Instant? = null,
) : RuntimeException(reason, cause)

/** Loaders whose plugins run on [platform]; a server running Paper also runs Spigot and Bukkit plugins. */
internal fun compatibleLoaders(platform: String): List<String> = when (platform) {
    "paper" -> listOf("paper", "spigot", "bukkit")
    "purpur" -> listOf("purpur", "paper", "spigot", "bukkit")
    "spigot" -> listOf("spigot", "bukkit")
    "waterfall" -> listOf("waterfall", "bungeecord")
    else -> listOf(platform)
}

/** Reads a value of a decoded JSON object, or `null` when it is missing or has another type. */
internal inline fun <reified T> Any?.field(name: String): T? = (this as? Map<*, *>)?.get(name) as? T

/** An `https` URI from a provider, or `null` for anything else, so links from responses cannot point elsewhere. */
internal fun secureUri(text: String?): URI? =
    text?.let { runCatching { URI.create(it) }.getOrNull() }?.takeIf { it.scheme == "https" && it.host != null }

internal fun instant(text: String?): Instant? = text?.let { runCatching { Instant.parse(it) }.getOrNull() }
