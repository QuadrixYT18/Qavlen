package de.quadrixyt.qavlen.adventure

import de.quadrixyt.qavlen.config.MessageComponents
import de.quadrixyt.qavlen.language.MessageTemplate
import de.quadrixyt.qavlen.language.Placeholder
import de.quadrixyt.qavlen.language.Translations
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.title.Title
import java.util.Locale

/**
 * Renders translations as Adventure components, with the same rules as [MessageComponents] in `qavlen-config`.
 *
 * Translation values are trusted MiniMessage markup written by the server owner. Placeholder values are not:
 * strings, numbers and dates are formatted for the locale and inserted as literal text, so they can never inject
 * tags such as `<click>`. [ComponentLike][net.kyori.adventure.text.ComponentLike] values are inserted as components.
 * Placeholders can be written as `{player}` or `<player>`, see [MessageComponents]. Every message may use `<prefix>`
 * or `{prefix}`, which renders the translation key [prefixKey] when it exists.
 *
 * For texts stored in a configuration, [de.quadrixyt.qavlen.config.ConfigSection.getComponent] needs no translations.
 */
public class AdventureMessages @JvmOverloads constructor(
    public val translations: Translations,
    private val miniMessage: MiniMessage = MiniMessage.miniMessage(),
    private val prefixKey: String = "prefix",
) {
    /** Renders [key] for [locale]. A missing key renders as the key itself. */
    public fun render(locale: Locale, key: String, placeholders: Map<String, Any?>): Component {
        val template = translations.template(locale, key) ?: return Component.text(key)
        return render(template, locale, placeholders)
    }

    public fun render(locale: Locale, key: String, vararg placeholders: Placeholder): Component =
        render(locale, key, placeholders.toMap())

    /** Renders an already resolved template. */
    public fun render(template: MessageTemplate, locale: Locale, placeholders: Map<String, Any?>): Component =
        render(template, locale, placeholders, prefix = true)

    private fun render(template: MessageTemplate, locale: Locale, placeholders: Map<String, Any?>, prefix: Boolean): Component {
        val prefixComponent = if (prefix && ("<prefix>" in template.source || "{prefix}" in template.source)) {
            translations.template(locale, prefixKey)?.let { render(it, locale, emptyMap(), prefix = false) } ?: Component.empty()
        } else {
            null
        }
        return MessageComponents.render(template, placeholders, prefixComponent, miniMessage) { segment ->
            translations.formatPlaceholder(segment, locale, placeholders)
        }
    }

    /**
     * Renders [text], MiniMessage with `{placeholders}` taken from a configuration value instead of a translation
     * file. Placeholder values are inserted as plain text, exactly as for translations.
     *
     * @throws IllegalArgumentException when [text] has malformed placeholders
     */
    public fun renderText(text: String, locale: Locale, placeholders: Map<String, Any?>): Component =
        render(MessageTemplate.parse(text), locale, placeholders)

    public fun renderText(text: String, locale: Locale, vararg placeholders: Placeholder): Component =
        renderText(text, locale, placeholders.toMap())

    /** Sends [text] rendered with [renderText] in the audience's locale. */
    public fun sendText(audience: Audience, text: String, vararg placeholders: Placeholder) {
        audience.sendMessage(renderText(text, locale(audience), placeholders.toMap()))
    }

    /**
     * Renders a multi-line translation as one component per line, for item lore, books or scoreboards.
     * Lines are split before MiniMessage parsing, so tags must be closed on the line that opens them.
     */
    public fun renderLines(locale: Locale, key: String, placeholders: Map<String, Any?>): List<Component> {
        val template = translations.template(locale, key) ?: return listOf(Component.text(key))
        return template.source.split('\n').map { line -> render(MessageTemplate.parse(line), locale, placeholders) }
    }

    public fun renderLines(locale: Locale, key: String, vararg placeholders: Placeholder): List<Component> =
        renderLines(locale, key, placeholders.toMap())

    /** The locale an audience reports through [Identity.LOCALE], or the default locale. */
    public fun locale(audience: Audience): Locale = audience.get(Identity.LOCALE).orElse(translations.defaultLocale)

    public fun send(audience: Audience, key: String, vararg placeholders: Placeholder) {
        audience.sendMessage(render(locale(audience), key, placeholders.toMap()))
    }

    public fun send(audience: Audience, key: String, placeholders: Map<String, Any?>) {
        audience.sendMessage(render(locale(audience), key, placeholders))
    }

    public fun actionBar(audience: Audience, key: String, vararg placeholders: Placeholder) {
        audience.sendActionBar(render(locale(audience), key, placeholders.toMap()))
    }

    /** Shows a title. [subtitleKey] may be `null`; [times] defaults to the client's standard fade timings. */
    @JvmOverloads
    public fun title(
        audience: Audience,
        titleKey: String,
        subtitleKey: String?,
        placeholders: Map<String, Any?> = emptyMap(),
        times: Title.Times? = null,
    ) {
        val locale = locale(audience)
        val subtitle = subtitleKey?.let { render(locale, it, placeholders) } ?: Component.empty()
        audience.showTitle(Title.title(render(locale, titleKey, placeholders), subtitle, times))
    }

    private fun Array<out Placeholder>.toMap(): Map<String, Any?> = associate { it.name to it.value }
}
