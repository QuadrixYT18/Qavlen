package de.quadrixyt.qavlen.paper

import org.bukkit.Server
import org.bukkit.entity.Player
import org.bukkit.plugin.Plugin
import org.bukkit.plugin.messaging.Messenger
import org.bukkit.plugin.messaging.PluginMessageListener
import java.lang.reflect.Proxy
import kotlin.test.Test
import kotlin.test.assertContentEquals
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse

class PluginChannelTest {
    private inline fun <reified T> proxy(crossinline handler: (String, Array<out Any?>) -> Any?): T =
        Proxy.newProxyInstance(T::class.java.classLoader, arrayOf(T::class.java)) { _, method, args ->
            when (method.name) {
                "hashCode" -> 0
                "equals" -> false
                "toString" -> T::class.java.simpleName
                else -> handler(method.name, args ?: emptyArray())
            }
        } as T

    private val calls = mutableListOf<String>()
    private var listener: PluginMessageListener? = null
    private val messenger = proxy<Messenger> { name, args ->
        calls += "$name:${args.getOrNull(1)}"
        when (name) {
            "registerIncomingPluginChannel" -> listener = args[2] as PluginMessageListener
            "isReservedChannel" -> return@proxy false
        }
        null
    }
    private val server = proxy<Server> { name, _ ->
        when (name) {
            "getMessenger" -> messenger
            "getOnlinePlayers" -> emptyList<Player>()
            else -> null
        }
    }
    private val plugin = proxy<Plugin> { name, _ -> if (name == "getServer") server else null }

    @Test
    fun `registers, filters by channel and unregisters`() {
        val received = mutableListOf<ByteArray>()
        val channel = PluginChannel(plugin, "example:sync") { _, data -> received += data }
        val player = proxy<Player> { _, _ -> null }

        listener!!.onPluginMessageReceived("other:channel", player, byteArrayOf(9))
        listener!!.onPluginMessageReceived("example:sync", player, byteArrayOf(1, 2))
        assertFalse(channel.send(byteArrayOf(3)))
        channel.close()

        assertEquals(1, received.size)
        assertContentEquals(byteArrayOf(1, 2), received.single())
        assertEquals(
            listOf(
                "isReservedChannel:null",
                "registerOutgoingPluginChannel:example:sync",
                "registerIncomingPluginChannel:example:sync",
                "unregisterIncomingPluginChannel:example:sync",
                "unregisterOutgoingPluginChannel:example:sync",
            ),
            calls,
        )
    }

    @Test
    fun `rejects invalid channel names`() {
        assertFailsWith<IllegalArgumentException> { PluginChannel(plugin, "NoNamespace") { _, _ -> } }
        assertFailsWith<IllegalArgumentException> { PluginChannel(plugin, "Example:Sync") { _, _ -> } }
    }
}
