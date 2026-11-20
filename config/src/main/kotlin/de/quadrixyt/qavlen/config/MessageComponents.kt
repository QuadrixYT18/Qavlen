package de.quadrixyt.qavlen.config

import de.quadrixyt.qavlen.language.MessageTemplate
import de.quadrixyt.qavlen.language.ValueFormatter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.Locale

/** Turns one `{placeholder}` of a template into the literal text inserted for it. */
public fun interface PlaceholderFormatter {
    public fun format(placeholder: MessageTemplate.Segment.Placeholder): String
}

/**
 * Renders MiniMessage texts with `{placeholders}` as Adventure components.
 *
 * The text itself is trusted markup written by the server owner, so tags, gradients and formatting work as usual.
 * Placeholder values are not trusted: strings, numbers and dates are inserted as literal text and can never inject
 * tags such as `<click>`. [ComponentLike] values are inserted as components. `<prefix>` renders the given prefix.
 *
 * Adventure and MiniMessage must be on the classpath; Paper and Velocity provide them.
 */
public object MessageComponents {
    /**
     * Renders [text] with [placeholders]; values are formatted with [Locale.ROOT] (`{coins:number}` gives `1,500`).
     *
     * @throws IllegalArgumentException when [text] has malformed placeholders
     */
    @JvmStatic
    @JvmOverloads
    public fun render(text: String, placeholders: Map<String, Any?> = emptyMap(), prefix: ComponentLike? = null): Component =
        render(MessageTemplate.parse(text), placeholders, prefix)

    /** Renders a parsed [template]; [formatter] defaults to the locale-independent [ValueFormatter]. */
    @JvmStatic
    @JvmOverloads
    public fun render(
        template: MessageTemplate,
        placeholders: Map<String, Any?>,
        prefix: ComponentLike?,
        miniMessage: MiniMessage = MiniMessage.miniMessage(),
        formatter: PlaceholderFormatter = PlaceholderFormatter { ValueFormatter.formatPlaceholder(it, placeholders, Locale.ROOT) },
    ): Component {
        val markup = StringBuilder()
        val resolvers = mutableListOf<TagResolver>()
        template.segments.forEach { segment ->
            when (segment) {
                is MessageTemplate.Segment.Text -> markup.append(segment.text)
                is MessageTemplate.Segment.Placeholder -> {
                    // Every value gets its own generated tag, so the value never becomes part of the markup.
                    val tag = "qavlen_arg_${resolvers.size}"
                    markup.append('<').append(tag).append('>')
                    val value = placeholders[segment.name]
                    resolvers += if (value is ComponentLike && placeholders.containsKey(segment.name)) {
                        Placeholder.component(tag, value)
                    } else {
                        Placeholder.unparsed(tag, formatter.format(segment))
                    }
                }
            }
        }
        if (prefix != null) resolvers += Placeholder.component("prefix", prefix)
        return miniMessage.deserialize(markup.toString(), TagResolver.resolver(resolvers))
    }

    /** Renders [text] line by line, for item lore, books or scoreboards; tags must be closed on their own line. */
    @JvmStatic
    @JvmOverloads
    public fun renderLines(text: String, placeholders: Map<String, Any?> = emptyMap(), prefix: ComponentLike? = null): List<Component> =
        text.split('\n').map { render(it, placeholders, prefix) }
}
