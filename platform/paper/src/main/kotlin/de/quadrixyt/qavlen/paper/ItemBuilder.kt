package de.quadrixyt.qavlen.paper

import com.destroystokyo.paper.profile.PlayerProfile
import de.quadrixyt.qavlen.adventure.AdventureMessages
import de.quadrixyt.qavlen.language.Placeholder
import io.papermc.paper.datacomponent.DataComponentType
import io.papermc.paper.datacomponent.DataComponentTypes
import io.papermc.paper.datacomponent.item.CustomModelData
import io.papermc.paper.datacomponent.item.ItemLore
import io.papermc.paper.datacomponent.item.ResolvableProfile
import io.papermc.paper.datacomponent.item.TooltipDisplay
import net.kyori.adventure.key.Key
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.format.TextDecoration
import org.bukkit.Material
import org.bukkit.enchantments.Enchantment
import org.bukkit.inventory.ItemStack
import java.util.Locale

/**
 * Builds item stacks through Paper's data component API.
 *
 * Names and lore are not italic unless the component sets italics itself, which avoids Minecraft's default italic
 * style for custom names. Names and lore can be rendered from translations, so menus show the viewer's language.
 *
 * ```kotlin
 * val icon = ItemBuilder.of(Material.DIAMOND)
 *     .name(messages, locale, "shop.diamond.name")
 *     .lore(messages, locale, "shop.diamond.lore", placeholder("price", 250))
 *     .glint(true)
 *     .data(priceKey, 250)
 *     .build()
 * ```
 *
 * The builder works on its own copy; [build] returns a new stack each time.
 */
public class ItemBuilder private constructor(private val item: ItemStack) {
    public fun amount(amount: Int): ItemBuilder = apply {
        require(amount in 1..item.maxStackSize) { "Amount must be between 1 and ${item.maxStackSize}" }
        item.amount = amount
    }

    /** Sets the custom name, without italics unless [name] defines them. */
    public fun name(name: ComponentLike): ItemBuilder = apply { item.setData(DataComponentTypes.CUSTOM_NAME, upright(name)) }

    /** Sets the name from a translation for [locale]. */
    public fun name(messages: AdventureMessages, locale: Locale, key: String, vararg placeholders: Placeholder): ItemBuilder =
        name(messages.render(locale, key, *placeholders))

    /** Replaces the lore. Each component is one line. */
    public fun lore(lines: List<ComponentLike>): ItemBuilder = apply {
        item.setData(DataComponentTypes.LORE, ItemLore.lore(lines.map(::upright)))
    }

    public fun lore(vararg lines: ComponentLike): ItemBuilder = lore(lines.toList())

    /** Sets the lore from a multi-line translation; every line of the translation becomes one lore line. */
    public fun lore(messages: AdventureMessages, locale: Locale, key: String, vararg placeholders: Placeholder): ItemBuilder =
        lore(messages.renderLines(locale, key, *placeholders))

    /** Forces the enchantment glint on (`true`), off (`false`) or back to the default (`null`). */
    public fun glint(glint: Boolean?): ItemBuilder = apply {
        if (glint ==
            null
        ) {
            item.resetData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE)
        } else {
            item.setData(DataComponentTypes.ENCHANTMENT_GLINT_OVERRIDE, glint)
        }
    }

    public fun unbreakable(): ItemBuilder = apply { item.setData(DataComponentTypes.UNBREAKABLE) }

    /** Adds an enchantment, also above its normal maximum level. */
    public fun enchant(enchantment: Enchantment, level: Int): ItemBuilder = apply { item.addUnsafeEnchantment(enchantment, level) }

    /** Uses a model from a resource pack, for example `myplugin:coin`. */
    public fun model(model: Key): ItemBuilder = apply { item.setData(DataComponentTypes.ITEM_MODEL, model) }

    /** Sets string values of the custom model data, read by `minecraft:select` item model definitions. */
    public fun customModelData(vararg strings: String): ItemBuilder = apply {
        item.setData(DataComponentTypes.CUSTOM_MODEL_DATA, CustomModelData.customModelData().addStrings(strings.toList()))
    }

    /** Hides the given components from the tooltip, for example `DataComponentTypes.ENCHANTMENTS`. */
    public fun hide(vararg components: DataComponentType): ItemBuilder = apply {
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().addHiddenComponents(*components))
    }

    /** Hides the whole tooltip, useful for filler items in menus. */
    public fun hideTooltip(): ItemBuilder = apply {
        item.setData(DataComponentTypes.TOOLTIP_DISPLAY, TooltipDisplay.tooltipDisplay().hideTooltip(true))
    }

    /** Shows [profile]'s skin on a player head. The client resolves missing textures itself. */
    public fun profile(profile: PlayerProfile): ItemBuilder = apply {
        item.setData(DataComponentTypes.PROFILE, ResolvableProfile.resolvableProfile(profile))
    }

    /** Stores a value in the item's persistent data container. */
    public fun <P : Any, C : Any> data(key: PersistentKey<P, C>, value: C): ItemBuilder = apply { key.set(item, value) }

    /** Returns a new stack; the builder can be reused afterwards. */
    public fun build(): ItemStack = item.clone()

    private fun upright(component: ComponentLike): Component =
        component.asComponent().decorationIfAbsent(TextDecoration.ITALIC, TextDecoration.State.FALSE)

    public companion object {
        @JvmStatic
        @JvmOverloads
        public fun of(material: Material, amount: Int = 1): ItemBuilder {
            require(material.isItem) { "$material is not an item" }
            return ItemBuilder(ItemStack.of(material, amount))
        }

        /** Starts from a copy of [item]; the original is not changed. */
        @JvmStatic
        public fun from(item: ItemStack): ItemBuilder = ItemBuilder(item.clone())
    }
}
