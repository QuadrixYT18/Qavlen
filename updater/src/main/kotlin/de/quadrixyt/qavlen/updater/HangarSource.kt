package de.quadrixyt.qavlen.updater

import java.net.URLEncoder
import java.util.concurrent.CompletableFuture

/**
 * Versions of a Hangar project, from `GET /api/v1/projects/{project}/versions`, newest first and paged. The platform
 * (`PAPER`, `VELOCITY` or `WATERFALL`) is sent as filter and checked on each version together with the Minecraft
 * version, which Hangar lists per platform; for Velocity those are Velocity versions. Only public versions count.
 */
internal class HangarSource(private val project: String) : ReleaseSource {
    override val description: String get() = "Hangar project '$project'"

    /** `owner/slug` for links to version pages; looked up once when only the slug was given. */
    @Volatile
    private var namespace: String? = project.takeIf { '/' in it }

    override fun releases(http: UpdateHttp, options: UpdateOptions): CompletableFuture<List<Release>> {
        val platform = options.platform?.let(::hangarPlatform)
        return namespace(http).thenCompose { namespace -> page(http, options, platform, namespace, 0, emptyList()) }
    }

    private fun namespace(http: UpdateHttp): CompletableFuture<String> {
        namespace?.let { return CompletableFuture.completedFuture(it) }
        return http.getJson("api/v1/projects/$project").thenApply { json ->
            val owner = json.field<Map<*, *>>("namespace").field<String>("owner")
            val slug = json.field<Map<*, *>>("namespace").field<String>("slug")
            if (owner == null || slug == null) throw UpdateCheckException("Hangar sent an unexpected response for $description")
            "$owner/$slug".also { namespace = it }
        }
    }

    /** Reads pages until one contains a version of the configured channel, at most [MAX_PAGES]. */
    private fun page(
        http: UpdateHttp,
        options: UpdateOptions,
        platform: String?,
        namespace: String,
        index: Int,
        found: List<Release>,
    ): CompletableFuture<List<Release>> {
        val query = buildList {
            add("limit" to PAGE_SIZE.toString())
            add("offset" to (index * PAGE_SIZE).toString())
            platform?.let { add("platform" to it) }
        }
        return http.getJson("api/v1/projects/$project/versions", query).thenCompose { json ->
            val versions =
                json.field<List<*>>("result") ?: throw UpdateCheckException("Hangar sent an unexpected response for $description")
            val total = json.field<Map<*, *>>("pagination").field<Number>("count")?.toInt() ?: 0
            val releases = found + versions.mapNotNull { release(it, platform, options.minecraftVersion, namespace) }
            val more = (index + 1) * PAGE_SIZE < total && index + 1 < MAX_PAGES
            if (more && releases.none { options.channel.includes(it.effectiveChannel) }) {
                page(http, options, platform, namespace, index + 1, releases)
            } else {
                CompletableFuture.completedFuture(releases)
            }
        }
    }

    private fun release(version: Any?, platform: String?, minecraftVersion: String?, namespace: String): Release? {
        val name = version.field<String>("name") ?: return null
        if ((version.field<String>("visibility") ?: "public") != "public") return null
        val downloads = version.field<Map<*, *>>("downloads").orEmpty()
        val dependencies = version.field<Map<*, *>>("platformDependencies").orEmpty()
        if (platform != null && platform !in downloads) return null
        if (minecraftVersion != null) {
            val lists = if (platform != null) listOf(dependencies[platform]) else dependencies.values.toList()
            if (lists.none { (it as? List<*>).orEmpty().contains(minecraftVersion) }) return null
        }
        val download = downloads[platform ?: downloads.keys.firstOrNull()]
        val encoded = URLEncoder.encode(name, Charsets.UTF_8).replace("+", "%20")
        return Release(
            version = name,
            channel = channel(version.field<Map<*, *>>("channel")),
            releaseUrl = secureUri("https://hangar.papermc.io/$namespace/versions/$encoded") ?: return null,
            downloadUrl = secureUri(download.field<String>("downloadUrl")) ?: secureUri(download.field<String>("externalUrl")),
            notes = version.field<String>("description"),
            publishedAt = instant(version.field<String>("createdAt")),
        )
    }

    private fun channel(channel: Map<*, *>?): UpdateChannel {
        val name = channel.field<String>("name").orEmpty().lowercase()
        val unstable = channel.field<List<*>>("flags").orEmpty().contains("UNSTABLE")
        return when {
            listOf("alpha", "snapshot", "dev", "nightly").any { it in name } -> UpdateChannel.ALPHA
            listOf("beta", "rc", "pre").any { it in name } || unstable -> UpdateChannel.BETA
            else -> UpdateChannel.STABLE
        }
    }

    private companion object {
        const val PAGE_SIZE = 25
        const val MAX_PAGES = 4

        /** Hangar's platform for a server software: Paper runs Paper, Purpur, Folia, Spigot and Bukkit plugins. */
        fun hangarPlatform(platform: String): String = when (platform) {
            "paper", "purpur", "folia", "spigot", "bukkit" -> "PAPER"
            "waterfall", "bungeecord" -> "WATERFALL"
            else -> platform.uppercase()
        }
    }
}
