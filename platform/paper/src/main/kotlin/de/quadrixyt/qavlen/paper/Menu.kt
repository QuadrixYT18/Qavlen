package de.quadrixyt.qavlen.paper

import net.kyori.adventure.text.Component
import org.bukkit.Bukkit
import org.bukkit.entity.Player
import org.bukkit.event.EventHandler
import org.bukkit.event.Listener
import org.bukkit.event.inventory.ClickType
import org.bukkit.event.inventory.InventoryClickEvent
import org.bukkit.event.inventory.InventoryCloseEvent
import org.bukkit.event.inventory.InventoryDragEvent
import org.bukkit.inventory.Inventory
import org.bukkit.inventory.InventoryHolder
import org.bukkit.inventory.ItemStack
import org.bukkit.plugin.Plugin
import java.util.function.BiConsumer
import java.util.function.Consumer
import java.util.function.Function

/** Handles a click on a menu slot. */
public fun interface ClickHandler {
    public fun onClick(click: MenuClick)
}

/** A click inside a [Menu]. The underlying event is already cancelled unless [Menu.allowItemMovement] is set. */
public class MenuClick internal constructor(public val menu: Menu, public val player: Player, public val event: InventoryClickEvent) {
    public val slot: Int get() = event.slot

    public val type: ClickType get() = event.click
}

/**
 * A chest inventory with per-slot click handlers. Items cannot be taken out or put in unless
 * [allowItemMovement] is enabled. Call [Menus.install] once per plugin so clicks reach their handlers.
 */
public open class Menu(title: Component, rows: Int) : InventoryHolder {
    private val handlers = arrayOfNulls<ClickHandler>(rows * 9)
    private val view: Inventory

    /** Called when a player closes this menu. */
    public var closeHandler: Consumer<Player>? = null

    public var allowItemMovement: Boolean = false

    init {
        require(rows in 1..6) { "A menu has 1 to 6 rows" }
        view = Bukkit.createInventory(this, rows * 9, title)
    }

    public val size: Int get() = handlers.size

    override fun getInventory(): Inventory = view

    @JvmOverloads
    public fun set(slot: Int, item: ItemStack?, handler: ClickHandler? = null) {
        require(slot in handlers.indices) { "Slot $slot is outside the menu" }
        view.setItem(slot, item)
        handlers[slot] = handler
    }

    public fun clear() {
        view.clear()
        handlers.fill(null)
    }

    /** Fills every empty slot with [item] without a click handler. */
    public fun fillEmpty(item: ItemStack) {
        handlers.indices.filter { view.getItem(it) == null }.forEach { view.setItem(it, item) }
    }

    public fun open(player: Player) {
        player.openInventory(view)
    }

    internal fun click(event: InventoryClickEvent) {
        val player = event.whoClicked as? Player ?: return
        if (event.clickedInventory !== view) {
            if (!allowItemMovement && (event.isShiftClick || event.click == ClickType.DOUBLE_CLICK)) event.isCancelled = true
            return
        }
        if (!allowItemMovement) event.isCancelled = true
        handlers.getOrNull(event.slot)?.onClick(MenuClick(this, player, event))
    }

    internal fun drag(event: InventoryDragEvent) {
        if (!allowItemMovement && event.rawSlots.any { it < size }) event.isCancelled = true
    }

    internal fun close(player: Player) {
        closeHandler?.accept(player)
    }
}

/**
 * A menu that shows [items] across pages in [contentSlots], with navigation buttons.
 *
 * @param render creates the icon of an item
 * @param action runs when an item's icon is clicked
 */
public class PaginatedMenu<T>(
    title: Component,
    rows: Int,
    private val contentSlots: List<Int>,
    items: List<T>,
    private val render: Function<T, ItemStack>,
    private val action: BiConsumer<MenuClick, T>,
) : Menu(title, rows) {
    private val items = items.toList()
    private var previous: Pair<Int, ItemStack>? = null
    private var next: Pair<Int, ItemStack>? = null

    public var page: Int = 0
        private set

    public val pageCount: Int get() = maxOf(1, (items.size + contentSlots.size - 1) / contentSlots.size)

    init {
        require(contentSlots.isNotEmpty() && contentSlots.all { it in 0 until rows * 9 }) { "Content slots must be inside the menu" }
    }

    /** Places navigation buttons, shown only when the respective page exists. */
    public fun navigation(previousSlot: Int, previousItem: ItemStack, nextSlot: Int, nextItem: ItemStack): PaginatedMenu<T> = apply {
        require(previousSlot !in contentSlots && nextSlot !in contentSlots) { "Navigation slots must not be content slots" }
        previous = previousSlot to previousItem
        next = nextSlot to nextItem
        show(page)
    }

    /** Shows [page], clamped to the available pages. */
    public fun show(page: Int) {
        this.page = page.coerceIn(0, pageCount - 1)
        contentSlots.forEach { set(it, null) }
        items.drop(this.page * contentSlots.size).take(contentSlots.size).forEachIndexed { index, item ->
            set(contentSlots[index], render.apply(item)) { click -> action.accept(click, item) }
        }
        previous?.let { (slot, icon) ->
            if (this.page > 0) set(slot, icon) { show(this.page - 1) } else set(slot, null)
        }
        next?.let { (slot, icon) ->
            if (this.page < pageCount - 1) set(slot, icon) { show(this.page + 1) } else set(slot, null)
        }
    }

    init {
        show(0)
    }
}

/** Routes inventory events to [Menu]s. */
public object Menus {
    @Volatile
    private var owner: Plugin? = null

    /** Registers the menu listener. Calling it again while the owning plugin is enabled has no effect. */
    @JvmStatic
    @Synchronized
    public fun install(plugin: Plugin) {
        if (owner?.isEnabled == true) return
        plugin.server.pluginManager.registerEvents(MenuListener, plugin)
        owner = plugin
    }

    private object MenuListener : Listener {
        @EventHandler(ignoreCancelled = true)
        fun onClick(event: InventoryClickEvent) {
            (event.inventory.holder as? Menu)?.click(event)
        }

        @EventHandler(ignoreCancelled = true)
        fun onDrag(event: InventoryDragEvent) {
            (event.inventory.holder as? Menu)?.drag(event)
        }

        @EventHandler
        fun onClose(event: InventoryCloseEvent) {
            val player = event.player as? Player ?: return
            (event.inventory.holder as? Menu)?.close(player)
        }
    }
}
