package de.quadrixyt.qavlen.updater

import java.net.URI
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertTrue

class HangarTest {
    /** Shaped like a real entry of GET /api/v1/projects/{project}/versions. */
    private fun version(
        name: String,
        channel: String = "Release",
        flags: List<String> = emptyList(),
        paper: List<String>? = listOf("26.2", "26.3"),
        velocity: List<String>? = null,
        visibility: String = "public",
    ): String {
        val platforms = listOfNotNull(paper?.let { "PAPER" to it }, velocity?.let { "VELOCITY" to it })
        val downloads = platforms.joinToString(",") { (platform, _) ->
            """"$platform":{"fileInfo":{"name":"Plugin.jar"},"externalUrl":null,"downloadUrl":"https://hangarcdn.papermc.io/plugins/Owner/Plugin/versions/$name/$platform/Plugin.jar"}"""
        }
        val dependencies = platforms.joinToString(",") { (platform, versions) -> """"$platform":${versions.joinToString(",", "[", "]") { "\"$it\"" }}""" }
        return """{"name":"$name","visibility":"$visibility","createdAt":"2026-11-07T09:38:30.877163Z","description":"About $name",
            "channel":{"name":"$channel","flags":${flags.joinToString(",", "[", "]") { "\"$it\"" }}},
            "downloads":{$downloads},"platformDependencies":{$dependencies}}"""
    }

    private fun page(vararg versions: String, count: Int = versions.size) =
        """{"pagination":{"count":$count,"limit":25,"offset":0},"result":[${versions.joinToString(",")}]}"""

    private fun checker(server: TestServer, project: String, current: String, options: UpdateOptions = UpdateOptions.defaults()) =
        UpdateChecker(HangarSource(project), current, options, server.uri)

    @Test
    fun `reports a newer release with Hangar links`() {
        TestServer { TestResponse(body = page(version("2.0.0+5"), version("1.0.0"))) }.use { server ->
            val result = checker(server, "Owner/Plugin", "1.0.0").use { it.check() }

            assertIs<UpdateResult.Available>(result)
            assertEquals("2.0.0+5", result.latestVersion)
            assertEquals(URI.create("https://hangar.papermc.io/Owner/Plugin/versions/2.0.0%2B5"), result.releaseUrl)
            assertTrue(result.downloadUrl.toString().startsWith("https://hangarcdn.papermc.io/"))
            assertEquals("About 2.0.0+5", result.releaseNotes)
            assertEquals("/api/v1/projects/Owner/Plugin/versions", server.requests.single().uri.path)
        }
    }

    @Test
    fun `channels are classified by name and flags`() {
        val body = page(version("3.0.0-SNAPSHOT+9", "Snapshot"), version("2.1.0", "Testing", listOf("UNSTABLE")), version("2.0.0"))
        TestServer { TestResponse(body = body) }.use { server ->
            assertEquals("2.0.0", (checker(server, "Owner/Plugin", "1.0.0").use { it.check() } as UpdateResult.Available).latestVersion)
            val beta = UpdateOptions.builder().channel(UpdateChannel.BETA).build()
            assertEquals("2.1.0", (checker(server, "Owner/Plugin", "1.0.0", beta).use { it.check() } as UpdateResult.Available).latestVersion)
        }
    }

    @Test
    fun `platform and Minecraft version filter the versions`() {
        val body = page(version("3.0.0", paper = null, velocity = listOf("3.4")), version("2.5.0", paper = listOf("26.4")), version("2.0.0"))
        TestServer { TestResponse(body = body) }.use { server ->
            val options = UpdateOptions.builder().platform("purpur").minecraftVersion("26.3").build()

            val result = checker(server, "Owner/Plugin", "1.0.0", options).use { it.check() }

            assertEquals("2.0.0", (result as UpdateResult.Available).latestVersion)
            assertEquals("PAPER", server.requests.single().query["platform"])
        }
    }

    @Test
    fun `a slug without owner is resolved once for the links`() {
        TestServer { request ->
            when (request.uri.path) {
                "/api/v1/projects/Plugin" -> TestResponse(body = """{"id":31,"name":"Plugin","namespace":{"owner":"Owner","slug":"Plugin"}}""")
                else -> TestResponse(body = page(version("2.0.0")))
            }
        }.use { server ->
            checker(server, "Plugin", "1.0.0", UpdateOptions.builder().cacheDuration(java.time.Duration.ZERO).build()).use { checker ->
                val result = checker.check() as UpdateResult.Available
                checker.check()

                assertEquals(URI.create("https://hangar.papermc.io/Owner/Plugin/versions/2.0.0"), result.releaseUrl)
                assertEquals(1, server.requests.count { it.uri.path == "/api/v1/projects/Plugin" })
            }
        }
    }

    @Test
    fun `later pages are read until a version of the channel appears`() {
        TestServer { request ->
            val offset = request.query["offset"]?.toInt() ?: 0
            val body = if (offset == 0) page(version("3.0.0-SNAPSHOT", "Snapshot"), count = 60) else page(version("2.0.0"), count = 60)
            TestResponse(body = body)
        }.use { server ->
            val result = checker(server, "Owner/Plugin", "1.0.0").use { it.check() }

            assertEquals("2.0.0", (result as UpdateResult.Available).latestVersion)
            assertEquals(listOf("0", "25"), server.requests.map { it.query["offset"] })
        }
    }

    @Test
    fun `hidden versions do not count and unknown projects fail`() {
        TestServer { TestResponse(body = page(version("9.0.0", visibility = "softDelete"), version("1.0.0"))) }.use { server ->
            assertEquals(UpdateResult.UpToDate("1.0.0", "1.0.0"), checker(server, "Owner/Plugin", "1.0.0").use { it.check() })
        }
        TestServer { TestResponse(404) }.use { server ->
            assertIs<UpdateResult.Failed>(checker(server, "Owner/Missing", "1.0.0").use { it.check() })
        }
    }
}
