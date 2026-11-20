package de.quadrixyt.qavlen.language

/**
 * A parsed translation value.
 *
 * Placeholders use `{name}` or `{name:style}`; `{{` and `}}` produce literal braces. Supported styles are
 * `number`, `integer`, `percent`, `currency`, a `DecimalFormat` pattern such as `number:#,##0.00`,
 * `date`, `time` and `datetime` (optionally with `:short`, `:medium`, `:long` or `:full`), `duration`, and
 * `choice:<ChoiceFormat pattern>` for simple plural forms, for example `{count:choice:0#no items|1#one item|1<{count} items}`.
 */
public class MessageTemplate private constructor(
    /** The unparsed source text. */
    public val source: String,
    public val segments: List<Segment>,
) {
    /** Names of all placeholders referenced by this template, including those nested in choice styles. */
    public val placeholderNames: Set<String> by lazy {
        segments.flatMapTo(linkedSetOf()) { segment ->
            when (segment) {
                is Segment.Text -> emptyList()
                is Segment.Placeholder -> listOf(segment.name) + nestedNames(segment.style)
            }
        }
    }

    public sealed interface Segment {
        public data class Text(val text: String) : Segment

        /** A placeholder reference; [style] is `null` when the value is formatted by its type alone. */
        public data class Placeholder(val name: String, val style: String?) : Segment
    }

    override fun toString(): String = source

    public companion object {
        private val namePattern = Regex("[A-Za-z0-9_.-]+")

        /** Parses [source], throwing [IllegalArgumentException] with the offending position on malformed input. */
        @JvmStatic
        public fun parse(source: String): MessageTemplate {
            val segments = mutableListOf<Segment>()
            val text = StringBuilder()
            var index = 0
            while (index < source.length) {
                val char = source[index]
                when {
                    char == '{' && source.startsWith("{{", index) -> text.append('{').also { index += 2 }
                    char == '}' && source.startsWith("}}", index) -> text.append('}').also { index += 2 }
                    char == '{' -> {
                        val end = closingBrace(source, index)
                        require(end > 0) { "Unclosed placeholder at index $index in \"$source\"" }
                        if (text.isNotEmpty()) segments += Segment.Text(text.toString()).also { text.clear() }
                        segments += placeholder(source.substring(index + 1, end), source)
                        index = end + 1
                    }
                    char == '}' -> throw IllegalArgumentException("Unmatched '}' at index $index in \"$source\"")
                    else -> text.append(char).also { index++ }
                }
            }
            if (text.isNotEmpty()) segments += Segment.Text(text.toString())
            return MessageTemplate(source, segments)
        }

        private fun placeholder(body: String, source: String): Segment.Placeholder {
            val name = body.substringBefore(':').trim()
            require(namePattern.matches(name)) { "Invalid placeholder name '$name' in \"$source\"" }
            val style = if (':' in body) body.substringAfter(':') else null
            style?.let { ValueFormatter.checkStyle(it) }
            return Segment.Placeholder(name, style)
        }

        /** Finds the brace closing the placeholder at [start], allowing nested braces inside choice patterns. */
        private fun closingBrace(source: String, start: Int): Int {
            var depth = 0
            for (index in start until source.length) {
                when (source[index]) {
                    '{' -> depth++
                    '}' -> if (--depth == 0) return index
                }
            }
            return -1
        }

        private fun nestedNames(style: String?): List<String> {
            if (style == null || !style.startsWith("choice:")) return emptyList()
            return parse(style.removePrefix("choice:").replace(Regex("(^|\\|)[^#<≤]*[#<≤]"), "$1")).placeholderNames.toList()
        }
    }
}
