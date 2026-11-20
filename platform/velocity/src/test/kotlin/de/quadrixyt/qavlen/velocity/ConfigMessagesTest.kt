package de.quadrixyt.qavlen.velocity

import com.velocitypowered.api.proxy.Player
import de.quadrixyt.qavlen.config.YamlConfig
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.serializer.plain.PlainTextComponentSerializer
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertEquals

class ConfigMessagesTest {
    @Test
    fun `configuration messages are sent to Velocity players as components`() {
        val received = mutableListOf<Component>()
        val player = Proxy.newProxyInstance(Player::class.java.classLoader, arrayOf(Player::class.java)) { _, method, args ->
            when (method.name) {
                "sendMessage" -> received.add(args!!.single() as Component).let { null }
                "getUsername" -> "Alex"
                else -> null
            }
        } as Player
        val config = YamlConfig.parse(
            """
            prefix: "<gray>[Server]</gray>"
            welcome: "<prefix> <gradient:gold:yellow>Welcome, {player}!</gradient>"
            """.trimIndent(),
        )

        player.sendMessage(config.getComponent("welcome", "player" to "<red>" + player.username))

        assertEquals(listOf("[Server] Welcome, <red>Alex!"), received.map(PlainTextComponentSerializer.plainText()::serialize))
    }
}
