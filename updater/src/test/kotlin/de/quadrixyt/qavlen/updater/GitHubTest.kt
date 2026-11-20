package de.quadrixyt.qavlen.updater

import java.net.URI
import java.util.function.Supplier
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class GitHubTest {
    /** Shaped like real responses of GET /repos/{owner}/{repo}/releases. */
    private fun release(tag: String, draft: Boolean = false, prerelease: Boolean = false, assets: List<String> = listOf("Plugin-$tag.jar")) = """
        {"tag_name":"$tag","name":"Release $tag","draft":$draft,"prerelease":$prerelease,
         "html_url":"https://github.com/owner/plugin/releases/tag/$tag","published_at":"2026-11-0${tag.length % 9 + 1}T00:00:00Z",
         "body":"Notes for $tag","assets":[${assets.joinToString(",") {
        """{"name":"$it","browser_download_url":"https://github.com/owner/plugin/releases/download/$tag/$it"}"""
    }}]}
    """.trimIndent()

    private fun checker(server: TestServer, current: String, options: UpdateOptions = UpdateOptions.defaults()) =
        UpdateChecker(GitHubSource("owner/plugin"), current, options, server.uri)

    @Test
    fun `tags are versions, drafts are skipped and pre-releases are betas`() {
        val body = "[${release("v3.0.0", draft = true)},${release("v2.1.0-rc.1", prerelease = true)},${release("v2.0.0")},${release("v1.0.0")}]"
        TestServer { TestResponse(body = body) }.use { server ->
            val stable = checker(server, "1.0.0").use { it.check() }
            val beta = checker(server, "1.0.0", UpdateOptions.builder().channel(UpdateChannel.BETA).build()).use { it.check() }

            assertIs<UpdateResult.Available>(stable)
            assertEquals("v2.0.0", stable.latestVersion)
            assertEquals(URI.create("https://github.com/owner/plugin/releases/tag/v2.0.0"), stable.releaseUrl)
            assertEquals(URI.create("https://github.com/owner/plugin/releases/download/v2.0.0/Plugin-v2.0.0.jar"), stable.downloadUrl)
            assertEquals("Notes for v2.0.0", stable.releaseNotes)
            assertEquals("v2.1.0-rc.1", (beta as UpdateResult.Available).latestVersion)
            assertEquals(UpdateChannel.BETA, beta.channel)
            assertEquals("/repos/owner/plugin/releases", server.requests.first().uri.path)
            assertEquals("application/vnd.github+json", server.requests.first().headers["accept"])
        }
    }

    @Test
    fun `a tag naming a pre-release is not stable even without the flag`() {
        TestServer { TestResponse(body = "[${release("2.0.0-beta.2")},${release("1.5.0")}]") }.use { server ->
            assertEquals("1.5.0", (checker(server, "1.0.0").use { it.check() } as UpdateResult.Available).latestVersion)
        }
    }

    @Test
    fun `the platform picks the matching jar and other assets have no download`() {
        val body = "[${release("2.0.0", assets = listOf("Plugin-Bukkit.jar", "Plugin-Velocity.jar", "sources.zip"))},${release("1.0.0", assets = listOf("notes.txt"))}]"
        TestServer { TestResponse(body = body) }.use { server ->
            val velocity = checker(server, "1.0.0", UpdateOptions.builder().platform("velocity").build()).use { it.check() }
            val any = checker(server, "1.0.0").use { it.check() }

            assertTrue((velocity as UpdateResult.Available).downloadUrl.toString().endsWith("Plugin-Velocity.jar"))
            assertTrue((any as UpdateResult.Available).downloadUrl.toString().endsWith("Plugin-Bukkit.jar"))
        }
        TestServer { TestResponse(body = "[${release("2.0.0", assets = listOf("notes.txt"))}]") }.use { server ->
            assertNull((checker(server, "1.0.0").use { it.check() } as UpdateResult.Available).downloadUrl)
        }
    }

    @Test
    fun `the token is sent but never appears in results`() {
        TestServer { TestResponse(401, "{\"message\":\"Bad credentials\"}") }.use { server ->
            val options = UpdateOptions.builder().githubToken(Supplier { "ghp_secret" }).build()

            val result = checker(server, "1.0.0", options).use { it.check() }

            assertEquals("Bearer ghp_secret", server.requests.single().headers["authorization"])
            assertIs<UpdateResult.Failed>(result)
            assertFalse("ghp_secret" in result.toString())
            assertFalse("ghp_secret" in options.toString())
        }
        TestServer { TestResponse(body = "[]") }.use { server ->
            checker(server, "1.0.0").use { it.check() }
            assertNull(server.requests.single().headers["authorization"])
        }
    }

    @Test
    fun `repository names are validated`() {
        assertTrue(runCatching { UpdateChecker.github("not-a-repository", "1.0") }.isFailure)
        assertTrue(runCatching { UpdateChecker.github("owner/repo/../x", "1.0") }.isFailure)
        UpdateChecker.github("QuadrixYT18/Qavlen", "1.0").close()
    }
}
