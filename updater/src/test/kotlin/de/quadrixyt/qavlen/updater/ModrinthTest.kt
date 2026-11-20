package de.quadrixyt.qavlen.updater

import java.net.URI
import java.time.Duration
import java.util.concurrent.CompletableFuture
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

class ModrinthTest {
    /** Shaped like real responses of GET /v2/project/{id}/version. */
    private fun version(
        number: String,
        type: String = "release",
        loaders: List<String> = listOf("paper"),
        games: List<String> = listOf("26.3"),
        date: String = "2026-11-01T10:00:00Z",
        status: String = "listed",
    ) = """
        {"id":"v${number.hashCode().toUInt()}","project_id":"AbCdEf12","name":"$number","version_number":"$number",
         "version_type":"$type","status":"$status","date_published":"$date","changelog":"Changes in $number",
         "loaders":${loaders.joinToString(",", "[", "]") { "\"$it\"" }},
         "game_versions":${games.joinToString(",", "[", "]") { "\"$it\"" }},
         "files":[{"url":"https://cdn.modrinth.com/data/AbCdEf12/versions/x/plugin-$number.jar","primary":true,"filename":"plugin-$number.jar"}]}
    """.trimIndent()

    private fun checker(server: TestServer, current: String, options: UpdateOptions = UpdateOptions.defaults()) =
        UpdateChecker(ModrinthSource("my-plugin"), current, options, server.uri)

    @Test
    fun `reports a newer stable release with links and notes`() {
        val body = "[${version("1.2.0-beta.1", "beta", date = "2026-11-03T00:00:00Z")},${version("1.1.0", date = "2026-11-02T00:00:00Z")},${version("1.0.0")}]"
        TestServer { TestResponse(body = body) }.use { server ->
            val result = checker(server, "1.0.0").use { it.check() }

            assertIs<UpdateResult.Available>(result)
            assertEquals("1.1.0", result.latestVersion)
            assertEquals(UpdateChannel.STABLE, result.channel)
            assertTrue(result.releaseUrl.toString().startsWith("https://modrinth.com/project/AbCdEf12/version/"))
            assertEquals(URI.create("https://cdn.modrinth.com/data/AbCdEf12/versions/x/plugin-1.1.0.jar"), result.downloadUrl)
            assertEquals("Changes in 1.1.0", result.releaseNotes)
            assertEquals("/v2/project/my-plugin/version", server.requests.single().uri.path)
            assertTrue(server.requests.single().headers.getValue("user-agent").startsWith("Qavlen-Updater"))
        }
    }

    @Test
    fun `channels include betas and alphas only when asked`() {
        val body = "[${version("1.3.0-alpha.1", "alpha")},${version("1.2.0-rc.1", "beta")},${version("1.1.0")}]"
        TestServer { TestResponse(body = body) }.use { server ->
            val beta = UpdateOptions.builder().channel(UpdateChannel.BETA).build()
            val alpha = UpdateOptions.builder().channel(UpdateChannel.ALPHA).build()

            assertEquals("1.2.0-rc.1", (checker(server, "1.0.0", beta).use { it.check() } as UpdateResult.Available).latestVersion)
            assertEquals("1.3.0-alpha.1", (checker(server, "1.0.0", alpha).use { it.check() } as UpdateResult.Available).latestVersion)
        }
    }

    @Test
    fun `platform and Minecraft version filter the versions`() {
        val body = "[${version("2.0.0", loaders = listOf("velocity"))},${version("1.9.0", games = listOf("26.4"))},${version("1.5.0", loaders = listOf("bukkit"))}]"
        TestServer { TestResponse(body = body) }.use { server ->
            val options = UpdateOptions.builder().platform("Paper").minecraftVersion("26.3").build()

            val result = checker(server, "1.0.0", options).use { it.check() }

            assertEquals("1.5.0", (result as UpdateResult.Available).latestVersion)
            val query = server.requests.single().query
            assertEquals("[\"paper\",\"spigot\",\"bukkit\"]", query["loaders"])
            assertEquals("[\"26.3\"]", query["game_versions"])
        }
    }

    @Test
    fun `installed, newer and unlisted versions are up to date`() {
        val body = "[${version("1.1.0")},${version("9.0.0", status = "archived")}]"
        TestServer { TestResponse(body = body) }.use { server ->
            assertEquals(UpdateResult.UpToDate("v1.1.0", "1.1.0"), checker(server, "v1.1.0").use { it.check() })
            assertEquals(UpdateResult.UpToDate("1.2.0-SNAPSHOT", "1.1.0"), checker(server, "1.2.0-SNAPSHOT").use { it.check() })
        }
        TestServer { TestResponse(body = "[]") }.use { server ->
            assertEquals(UpdateResult.UpToDate("1.0.0", null), checker(server, "1.0.0").use { it.check() })
        }
    }

    @Test
    fun `versions that cannot be compared are failures, not up to date`() {
        TestServer { TestResponse(body = "[${version("1.1.0")}]") }.use { server ->
            val result = checker(server, "dev-build").use { it.check() }

            assertIs<UpdateResult.Failed>(result)
            assertTrue("dev-build" in result.reason && "1.1.0" in result.reason, result.reason)
        }
    }

    @Test
    fun `errors become failures with a reason`() {
        TestServer { TestResponse(404, "{\"error\":\"not_found\"}") }.use { server ->
            val result = checker(server, "1.0.0").use { it.check() }
            assertIs<UpdateResult.Failed>(result)
            assertTrue("Not found" in result.reason, result.reason)
        }
        TestServer { TestResponse(body = "<html>maintenance</html>") }.use { server ->
            assertTrue("not valid JSON" in (checker(server, "1.0.0").use { it.check() } as UpdateResult.Failed).reason)
        }
    }

    @Test
    fun `a slow provider times out`() {
        TestServer { TestResponse(body = "[]", delayMillis = 1500) }.use { server ->
            val options = UpdateOptions.builder().timeout(Duration.ofMillis(300)).build()

            val result = checker(server, "1.0.0", options).use { it.check() }

            assertTrue("did not answer in time" in (result as UpdateResult.Failed).reason, result.reason)
        }
    }

    @Test
    fun `concurrent checks share one request and results are cached`() {
        TestServer { TestResponse(body = "[${version("1.1.0")}]", delayMillis = 300) }.use { server ->
            checker(server, "1.0.0").use { checker ->
                val results = List(8) { checker.checkAsync() }.map(CompletableFuture<UpdateResult>::join)
                checker.check()

                assertTrue(results.all { it is UpdateResult.Available })
                assertEquals(1, server.requests.size)
                checker.invalidate()
                checker.check()
                assertEquals(2, server.requests.size)
            }
        }
    }

    @Test
    fun `failures are not cached and cancelling one caller does not cancel the check`() {
        var status = 500
        TestServer { TestResponse(status, if (status == 200) "[${version("1.1.0")}]" else "", delayMillis = 200) }.use { server ->
            checker(server, "1.0.0").use { checker ->
                val cancelled = checker.checkAsync()
                val other = checker.checkAsync()
                cancelled.cancel(true)

                assertIs<UpdateResult.Failed>(other.join())
                status = 200
                assertIs<UpdateResult.Available>(checker.check())
                assertNull((checker.lastResult as? UpdateResult.Failed))
            }
        }
    }

    @Test
    fun `redirects are only followed within the provider's host`() {
        TestServer { request ->
            when (request.uri.path) {
                "/v2/project/my-plugin/version" -> TestResponse(301, headers = mapOf("Location" to "/v2/project/AbCdEf12/version"))
                "/v2/project/AbCdEf12/version" -> TestResponse(body = "[${version("1.1.0")}]")
                else -> TestResponse(404)
            }
        }.use { server -> assertIs<UpdateResult.Available>(checker(server, "1.0.0").use { it.check() }) }

        TestServer { TestResponse(302, headers = mapOf("Location" to "https://evil.example/versions")) }.use { server ->
            val result = checker(server, "1.0.0").use { it.check() }
            assertTrue("unexpected location" in (result as UpdateResult.Failed).reason)
        }
    }
}
