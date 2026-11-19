package de.quadrixyt.qavlen.paper

import io.papermc.paper.persistence.PersistentDataViewHolder
import org.bukkit.NamespacedKey
import org.bukkit.inventory.ItemStack
import org.bukkit.persistence.PersistentDataHolder
import org.bukkit.persistence.PersistentDataType
import org.bukkit.plugin.Plugin

/**
 * A typed key into persistent data containers, so the key and its data type are declared once.
 *
 * ```kotlin
 * val owner = PersistentKey(plugin, "owner", PersistentDataType.STRING)
 * owner.set(item, player.name)
 * val name: String? = owner.get(item)
 * ```
 *
 * Item stacks are edited through `ItemStack.editPersistentDataContainer`, which avoids copying item meta.
 */
public class PersistentKey<P : Any, C : Any>(public val key: NamespacedKey, public val type: PersistentDataType<P, C>) {
    public constructor(plugin: Plugin, name: String, type: PersistentDataType<P, C>) : this(NamespacedKey(plugin, name), type)

    public fun get(holder: PersistentDataViewHolder): C? = holder.persistentDataContainer.get(key, type)

    public fun getOrDefault(holder: PersistentDataViewHolder, default: C): C = get(holder) ?: default

    public fun has(holder: PersistentDataViewHolder): Boolean = holder.persistentDataContainer.has(key, type)

    public fun set(holder: PersistentDataHolder, value: C) {
        holder.persistentDataContainer.set(key, type, value)
    }

    public fun remove(holder: PersistentDataHolder) {
        holder.persistentDataContainer.remove(key)
    }

    /** @return `false` when the stack cannot store data, for example air */
    public fun set(item: ItemStack, value: C): Boolean = item.editPersistentDataContainer { it.set(key, type, value) }

    public fun remove(item: ItemStack): Boolean = item.editPersistentDataContainer { it.remove(key) }

    override fun toString(): String = "PersistentKey($key)"
}
