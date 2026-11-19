package de.quadrixyt.qavlen.adventure

import de.quadrixyt.qavlen.language.MessageTemplate
import de.quadrixyt.qavlen.language.Placeholder
import de.quadrixyt.qavlen.language.Translations
import net.kyori.adventure.audience.Audience
import net.kyori.adventure.identity.Identity
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import net.kyori.adventure.title.Title
import java.util.Locale
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder as MiniPlaceholder

/**
 * Renders translations as Adventure components.
 *
 * Translation values are trusted MiniMessage markup written by the server owner. Placeholder values are not:
 * strings, numbers and dates are inserted as literal text and can never inject tags such as `<click>`.
 * [ComponentLike] values are inserted as components. Every message may use `<prefix>`, which renders the
 * translation key [prefixKey] when it exists.
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
        val markup = StringBuilder()
        val resolvers = mutableListOf<TagResolver>()
        template.segments.forEach { segment ->
            when (segment) {
                is MessageTemplate.Segment.Text -> markup.append(segment.text)
                is MessageTemplate.Segment.Placeholder -> {
                    val tag = "qavlen_arg_${resolvers.size}"
                    markup.append('<').append(tag).append('>')
                    val value = placeholders[segment.name]
                    resolvers += if (value is ComponentLike && placeholders.containsKey(segment.name)) {
                        MiniPlaceholder.component(tag, value)
                    } else {
                        MiniPlaceholder.unparsed(tag, translations.formatPlaceholder(segment, locale, placeholders))
                    }
                }
            }
        }
        if (prefix && "<prefix>" in template.source) {
            val prefixTemplate = translations.template(locale, prefixKey)
            val rendered = prefixTemplate?.let { render(it, locale, emptyMap(), prefix = false) } ?: Component.empty()
            resolvers += MiniPlaceholder.component("prefix", rendered)
        }
        return miniMessage.deserialize(markup.toString(), TagResolver.resolver(resolvers))
    }

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
