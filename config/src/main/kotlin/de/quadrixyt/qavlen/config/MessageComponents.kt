package de.quadrixyt.qavlen.config

import de.quadrixyt.qavlen.language.MessageTemplate
import de.quadrixyt.qavlen.language.ValueFormatter
import net.kyori.adventure.text.Component
import net.kyori.adventure.text.ComponentLike
import net.kyori.adventure.text.minimessage.MiniMessage
import net.kyori.adventure.text.minimessage.tag.Tag
import net.kyori.adventure.text.minimessage.tag.resolver.Placeholder
import net.kyori.adventure.text.minimessage.tag.resolver.TagResolver
import java.util.Locale

/** Turns one `{placeholder}` of a template into the literal text inserted for it. */
public fun interface PlaceholderFormatter {
    public fun format(placeholder: MessageTemplate.Segment.Placeholder): String
}

/**
 * Renders MiniMessage texts with placeholders as Adventure components.
 *
 * The text itself is trusted markup written by the server owner, so tags, gradients and formatting work as usual.
 * A placeholder can be written as `{player}` or as a tag, `<player>`; styles work in both forms, as `{coins:number}`
 * and `<coins:number>`. The tag form needs a lowercase name without dots that is not a MiniMessage tag such as `red`
 * or `bold`; other names only work in braces. Placeholder values are not trusted: strings, numbers and dates are
 * inserted as literal text and can never inject tags such as `<click>`. [ComponentLike] values are inserted as
 * components. `<prefix>` and `{prefix}` render the given prefix, unless a placeholder named `prefix` is passed.
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
        val configuredPrefix = prefix.takeUnless { placeholders.containsKey(PREFIX) }
        template.segments.forEach { segment ->
            when (segment) {
                is MessageTemplate.Segment.Text -> markup.append(segment.text)
                is MessageTemplate.Segment.Placeholder -> {
                    // Every value gets its own generated tag, so the value never becomes part of the markup.
                    val tag = "qavlen_arg_${resolvers.size}"
                    markup.append('<').append(tag).append('>')
                    val value = placeholders[segment.name]
                    resolvers += when {
                        segment.name == PREFIX && configuredPrefix != null -> Placeholder.component(tag, configuredPrefix)
                        value is ComponentLike && placeholders.containsKey(segment.name) -> Placeholder.component(tag, value)
                        else -> Placeholder.unparsed(tag, formatter.format(segment))
                    }
                }
            }
        }
        placeholders.forEach { (name, value) -> tagPlaceholder(name, value, miniMessage, formatter)?.let(resolvers::add) }
        if (configuredPrefix != null) resolvers += Placeholder.component(PREFIX, configuredPrefix)
        return miniMessage.deserialize(markup.toString(), TagResolver.resolver(resolvers))
    }

    /**
     * Makes [name] usable as `<name>` or `<name:style>`, unless the name cannot be a tag or MiniMessage already
     * defines a tag of that name, so `<red>` always stays a color.
     */
    private fun tagPlaceholder(name: String, value: Any?, miniMessage: MiniMessage, formatter: PlaceholderFormatter): TagResolver? {
        if (!tagName.matches(name) || miniMessage.tags().has(name)) return null
        return TagResolver.resolver(name) { arguments, _ ->
            if (value is ComponentLike) return@resolver Tag.selfClosingInserting(value)
            val style = generateSequence { if (arguments.hasNext()) arguments.pop().value() else null }.joinToString(":")
            Tag.selfClosingInserting(Component.text(formatter.format(MessageTemplate.Segment.Placeholder(name, style.ifEmpty { null }))))
        }
    }

    private const val PREFIX = "prefix"
    private val tagName = Regex("[a-z0-9_-]+")

    /** Renders [text] line by line, for item lore, books or scoreboards; tags must be closed on their own line. */
    @JvmStatic
    @JvmOverloads
    public fun renderLines(text: String, placeholders: Map<String, Any?> = emptyMap(), prefix: ComponentLike? = null): List<Component> =
        text.split('\n').map { render(it, placeholders, prefix) }
}
