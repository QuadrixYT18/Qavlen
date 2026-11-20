package de.quadrixyt.qavlen.language

import de.quadrixyt.qavlen.core.Durations
import java.text.ChoiceFormat
import java.text.DecimalFormat
import java.text.DecimalFormatSymbols
import java.text.NumberFormat
import java.time.Duration
import java.time.Instant
import java.time.LocalDate
import java.time.LocalDateTime
import java.time.LocalTime
import java.time.OffsetDateTime
import java.time.ZoneId
import java.time.ZonedDateTime
import java.time.format.DateTimeFormatter
import java.time.format.FormatStyle
import java.time.temporal.TemporalAccessor
import java.util.Date
import java.util.Locale

/** Formats placeholder values with the JDK's locale-aware formatters. */
public object ValueFormatter {
    /**
     * Formats [value] for [locale]. [style] is the part after the colon in `{name:style}` or `null`.
     *
     * @throws IllegalArgumentException when [style] does not apply to the value
     */
    @JvmStatic
    @JvmOverloads
    public fun format(value: Any?, style: String?, locale: Locale, zone: ZoneId = ZoneId.systemDefault()): String {
        if (value == null) return "null"
        val kind = style?.substringBefore(':')
        val option = style?.substringAfter(':', "")?.takeIf { ':' in style }
        return when (kind) {
            null -> formatPlain(value, locale, zone)
            "number", "integer", "percent", "currency" -> numberFormat(kind, option, locale).format(number(value, style))
            "date", "time", "datetime" -> dateFormat(kind, option, value, locale).format(temporal(value, zone, style))
            "duration" -> Durations.format(value as? Duration ?: throw IllegalArgumentException("'$style' requires a Duration"))
            else -> throw IllegalArgumentException("Unknown placeholder style '$style'")
        }
    }

    /**
     * Formats one placeholder of a template with its value from [placeholders]. A placeholder without a value is
     * kept as `{name}`; `choice` styles pick a sub-template, which is formatted with the same values.
     *
     * @throws IllegalArgumentException when the style does not apply to the value
     */
    @JvmStatic
    @JvmOverloads
    public fun formatPlaceholder(
        segment: MessageTemplate.Segment.Placeholder,
        placeholders: Map<String, Any?>,
        locale: Locale,
        zone: ZoneId = ZoneId.systemDefault(),
    ): String {
        if (!placeholders.containsKey(segment.name)) return "{${segment.name}}"
        val value = placeholders[segment.name]
        val style = segment.style
        if (style == null || !style.startsWith("choice:")) return format(value, style, locale, zone)
        val number = value as? Number ?: throw IllegalArgumentException("'${segment.name}' must be a number for choice formatting")
        val chosen = MessageTemplate.parse(ChoiceFormat(style.removePrefix("choice:")).format(number.toDouble()))
        return chosen.segments.joinToString("") { part ->
            when (part) {
                is MessageTemplate.Segment.Text -> part.text
                is MessageTemplate.Segment.Placeholder -> formatPlaceholder(part, placeholders, locale, zone)
            }
        }
    }

    internal fun checkStyle(style: String) {
        val kind = style.substringBefore(':')
        val option = style.substringAfter(':', "").takeIf { ':' in style }
        when (kind) {
            "number" -> option?.let { DecimalFormat(it) }
            "integer", "percent", "currency", "duration" -> require(option == null) { "Style '$kind' takes no option" }
            "date", "time", "datetime" -> option?.let(::formatStyle)
            "choice" -> {
                val choices = ChoiceFormat(requireNotNull(option) { "Style 'choice' requires a pattern" })
                // Each choice is itself a template, so its placeholders are checked when the file is loaded.
                choices.formats.forEach { MessageTemplate.parse(it.toString()) }
            }
            else -> throw IllegalArgumentException("Unknown placeholder style '$style'")
        }
    }

    private fun formatPlain(value: Any, locale: Locale, zone: ZoneId): String = when (value) {
        is Byte, is Short, is Int, is Long -> NumberFormat.getIntegerInstance(locale).format(value)
        is Number -> NumberFormat.getInstance(locale).format(value)
        is Duration -> Durations.format(value)
        is Instant -> DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(value.atZone(zone))
        is Date -> formatPlain(value.toInstant(), locale, zone)
        is ZonedDateTime, is OffsetDateTime, is LocalDateTime ->
            DateTimeFormatter.ofLocalizedDateTime(FormatStyle.MEDIUM).withLocale(locale).format(value as TemporalAccessor)
        is LocalDate -> DateTimeFormatter.ofLocalizedDate(FormatStyle.MEDIUM).withLocale(locale).format(value)
        is LocalTime -> DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT).withLocale(locale).format(value)
        else -> value.toString()
    }

    private fun numberFormat(kind: String, option: String?, locale: Locale): NumberFormat = when (kind) {
        "integer" -> NumberFormat.getIntegerInstance(locale)
        "percent" -> NumberFormat.getPercentInstance(locale)
        "currency" -> NumberFormat.getCurrencyInstance(locale)
        else -> option?.let { DecimalFormat(it, DecimalFormatSymbols.getInstance(locale)) } ?: NumberFormat.getInstance(locale)
    }

    private fun dateFormat(kind: String, option: String?, value: Any, locale: Locale): DateTimeFormatter {
        val style = option?.let(::formatStyle) ?: if (kind == "time") FormatStyle.SHORT else FormatStyle.MEDIUM
        val formatter = when {
            kind == "date" -> DateTimeFormatter.ofLocalizedDate(style)
            kind == "time" -> DateTimeFormatter.ofLocalizedTime(style)
            // LONG and FULL date-time styles need a zone, which local values do not carry.
            value is LocalDateTime && style < FormatStyle.MEDIUM -> DateTimeFormatter.ofLocalizedDateTime(style, FormatStyle.MEDIUM)
            else -> DateTimeFormatter.ofLocalizedDateTime(style)
        }
        return formatter.withLocale(locale)
    }

    private fun formatStyle(option: String): FormatStyle = when (option.lowercase()) {
        "short" -> FormatStyle.SHORT
        "medium" -> FormatStyle.MEDIUM
        "long" -> FormatStyle.LONG
        "full" -> FormatStyle.FULL
        else -> throw IllegalArgumentException("Unknown date style '$option'")
    }

    private fun number(value: Any, style: String): Number =
        value as? Number ?: throw IllegalArgumentException("'$style' requires a number, got ${value.javaClass.simpleName}")

    private fun temporal(value: Any, zone: ZoneId, style: String): TemporalAccessor = when (value) {
        is Instant -> value.atZone(zone)
        is Date -> value.toInstant().atZone(zone)
        is TemporalAccessor -> value
        else -> throw IllegalArgumentException("'$style' requires a date or time, got ${value.javaClass.simpleName}")
    }
}
