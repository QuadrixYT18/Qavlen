package de.quadrixyt.qavlen.updater

import java.util.concurrent.CompletableFuture

/**
 * Versions of a Modrinth project, from `GET /v2/project/{id|slug}/version`. Platform and Minecraft version are sent
 * as the `loaders` and `game_versions` filters and checked again on each version. Only listed versions count.
 */
internal class ModrinthSource(private val project: String) : ReleaseSource {
    override val description: String get() = "Modrinth project '$project'"

    override fun releases(http: UpdateHttp, options: UpdateOptions): CompletableFuture<List<Release>> {
        val loaders = options.platform?.let(::compatibleLoaders)
        val query = buildList {
            loaders?.let { add("loaders" to http.jsonArray(it)) }
            options.minecraftVersion?.let { add("game_versions" to http.jsonArray(listOf(it))) }
        }
        return http.getJson("v2/project/$project/version", query).thenApply { json ->
            val versions = json as? List<*> ?: throw UpdateCheckException("Modrinth sent an unexpected response for $description")
            versions.mapNotNull { release(it, loaders, options.minecraftVersion) }.sortedByDescending { it.publishedAt }
        }
    }

    private fun release(version: Any?, loaders: List<String>?, minecraftVersion: String?): Release? {
        val number = version.field<String>("version_number") ?: return null
        val id = version.field<String>("id") ?: return null
        val projectId = version.field<String>("project_id") ?: return null
        if ((version.field<String>("status") ?: "listed") != "listed") return null
        if (loaders != null && version.field<List<*>>("loaders").orEmpty().none { it in loaders }) return null
        if (minecraftVersion != null && minecraftVersion !in version.field<List<*>>("game_versions").orEmpty()) return null
        val channel = when (version.field<String>("version_type")) {
            "alpha" -> UpdateChannel.ALPHA
            "beta" -> UpdateChannel.BETA
            else -> UpdateChannel.STABLE
        }
        val files = version.field<List<*>>("files").orEmpty()
        val file = files.firstOrNull { it.field<Boolean>("primary") == true } ?: files.firstOrNull()
        return Release(
            version = number,
            channel = channel,
            releaseUrl = requireNotNull(secureUri("https://modrinth.com/project/$projectId/version/$id")),
            downloadUrl = secureUri(file.field<String>("url")),
            notes = version.field<String>("changelog"),
            publishedAt = instant(version.field<String>("date_published")),
        )
    }
}
