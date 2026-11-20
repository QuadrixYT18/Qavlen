package de.quadrixyt.qavlen.updater

import java.util.concurrent.CompletableFuture

/**
 * Releases of a GitHub repository, from `GET /repos/{owner}/{repo}/releases`. The tag is the version; drafts are
 * skipped and pre-releases count as betas. GitHub has no Minecraft metadata, so only the platform is used, to pick
 * a `.jar` asset whose name contains it.
 */
internal class GitHubSource(private val repository: String) : ReleaseSource {
    override val description: String get() = "GitHub repository '$repository'"

    override fun releases(http: UpdateHttp, options: UpdateOptions): CompletableFuture<List<Release>> {
        val headers = buildMap {
            put("Accept", "application/vnd.github+json")
            put("X-GitHub-Api-Version", "2022-11-28")
            options.githubToken?.get()?.takeIf { it.isNotBlank() }?.let { put("Authorization", "Bearer $it") }
        }
        return http.getJson("repos/$repository/releases", listOf("per_page" to PAGE_SIZE.toString()), headers).thenApply { json ->
            val releases = json as? List<*> ?: throw UpdateCheckException("GitHub sent an unexpected response for $description")
            releases.mapNotNull { release(it, options.platform) }.sortedByDescending { it.publishedAt }
        }
    }

    private fun release(release: Any?, platform: String?): Release? {
        if (release.field<Boolean>("draft") == true) return null
        val tag = release.field<String>("tag_name") ?: return null
        val page = secureUri(release.field<String>("html_url")) ?: return null
        val jars = release.field<List<*>>("assets").orEmpty().filter {
            it.field<String>("name")?.endsWith(".jar", ignoreCase = true) == true
        }
        val asset =
            platform?.let { name -> jars.firstOrNull { it.field<String>("name")!!.contains(name, ignoreCase = true) } }
                ?: jars.firstOrNull()
        return Release(
            version = tag,
            channel = if (release.field<Boolean>("prerelease") == true) UpdateChannel.BETA else UpdateChannel.STABLE,
            releaseUrl = page,
            downloadUrl = secureUri(asset.field<String>("browser_download_url")),
            notes = release.field<String>("body"),
            publishedAt = instant(release.field<String>("published_at")),
        )
    }

    private companion object {
        const val PAGE_SIZE = 30
    }
}
