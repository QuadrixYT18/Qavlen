package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.ConfigOptions
import de.quadrixyt.qavlen.config.YamlConfig
import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

class ConfigBindingTest {
    enum class Mode { FAST, SAFE }

    data class Database(
        val host: String = "localhost",
        val port: Int = 3306,
        val poolSize: Int = 4,
        val timeout: Duration = Duration.ofSeconds(10),
        val mode: Mode = Mode.SAFE,
    )

    data class Settings(val database: Database = Database(), val admins: List<String> = emptyList(), val motd: String)

    @Test
    fun `binds kebab case keys, compact durations and defaults`() {
        val config = YamlConfig.parse(
            """
            motd: Welcome
            admins: [Alex, Sam]
            database:
              host: db.internal
              pool-size: 8
              timeout: 1m30s
              mode: fast
              unknown-key: ignored
            """.trimIndent(),
        )

        val settings = config.bind<Settings>()

        assertEquals(Settings(Database("db.internal", 3306, 8, Duration.ofSeconds(90), Mode.FAST), listOf("Alex", "Sam"), "Welcome"), settings)
        assertEquals(settings.database, config.bind<Database>("database"))
        assertEquals(Database(), config.bind<Database>("missing.section"))
    }

    @Test
    fun `reports the full path of invalid values`() {
        val config = YamlConfig.parse("database:\n  port: many\n  timeout: soon\n")

        val wrongType = assertFailsWith<ConfigException> { config.bind<Database>("database") }
        assertTrue("'database.port'" in wrongType.message!!, wrongType.message)

        val missing = assertFailsWith<ConfigException> { YamlConfig.parse("admins: []").bind<Settings>() }
        assertTrue("motd" in missing.message!!, missing.message)

        val badDuration = assertFailsWith<ConfigException> { YamlConfig.parse("timeout: soon").bind<Database>() }
        assertTrue("'timeout'" in badDuration.message!!, badDuration.message)
    }

    @Test
    fun `writes sections and provides defaults`(@TempDir directory: Path) {
        val file = directory.resolve("config.yml")
        val config = YamlConfig.load(file, ConfigOptions { defaults(mapOf("database" to ConfigBinding.toMap(Database()))) })

        assertTrue("pool-size: 4" in file.readText(), file.readText())
        assertTrue("timeout: 10s" in file.readText(), file.readText())

        ConfigBinding.write(config, "database", Database(host = "remote", timeout = Duration.ofMinutes(2)))
        config.save()

        assertEquals(Database(host = "remote", timeout = Duration.ofMinutes(2)), YamlConfig.load(file).bind<Database>("database"))
    }
}
