package de.quadrixyt.qavlen.paper

import org.bukkit.event.Event
import org.bukkit.event.EventPriority
import org.bukkit.event.HandlerList
import org.bukkit.event.Listener
import org.bukkit.plugin.Plugin
import java.util.function.Consumer

/** A registered event handler. Closing it unregisters the handler. */
public class EventSubscription internal constructor(private val listener: Listener) : AutoCloseable {
    override fun close() {
        HandlerList.unregisterAll(listener)
    }
}

/**
 * Registers single event handlers without a `Listener` class and `@EventHandler` methods.
 *
 * ```kotlin
 * val subscription = Events.listen(plugin, PlayerJoinEvent::class.java) { event -> ... }
 * ```
 */
public object Events {
    /**
     * Calls [handler] for [type] and its subclasses that are dispatched to [type]'s handler list.
     *
     * @param ignoreCancelled skip events another handler already cancelled
     */
    @JvmStatic
    @JvmOverloads
    public fun <T : Event> listen(
        plugin: Plugin,
        type: Class<T>,
        priority: EventPriority = EventPriority.NORMAL,
        ignoreCancelled: Boolean = false,
        handler: Consumer<in T>,
    ): EventSubscription {
        val listener = object : Listener {}
        plugin.server.pluginManager.registerEvent(type, listener, priority, { _, event ->
            if (type.isInstance(event)) handler.accept(type.cast(event))
        }, plugin, ignoreCancelled)
        return EventSubscription(listener)
    }
}

/** Kotlin shorthand: `plugin.listen<PlayerJoinEvent> { event -> ... }`. */
public inline fun <reified T : Event> Plugin.listen(
    priority: EventPriority = EventPriority.NORMAL,
    ignoreCancelled: Boolean = false,
    handler: Consumer<in T>,
): EventSubscription = Events.listen(this, T::class.java, priority, ignoreCancelled, handler)
