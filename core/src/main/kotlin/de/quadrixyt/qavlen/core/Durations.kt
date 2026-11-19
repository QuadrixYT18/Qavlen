package de.quadrixyt.qavlen.core

import java.time.Duration

/** Parses and formats compact durations such as `1h30m`, `45s` or `2d 4h`, as typed by users in commands. */
public object Durations {
    private val units = linkedMapOf(
        "w" to Duration.ofDays(7),
        "d" to Duration.ofDays(1),
        "h" to Duration.ofHours(1),
        "m" to Duration.ofMinutes(1),
        "s" to Duration.ofSeconds(1),
        "ms" to Duration.ofMillis(1),
    )
    private val part = Regex("(\\d+)(ms|w|d|h|m|s)", RegexOption.IGNORE_CASE)

    /**
     * Parses a compact duration. ISO-8601 input (`PT5M`) is accepted as well.
     *
     * @throws IllegalArgumentException when [text] is empty, malformed or negative
     */
    @JvmStatic
    public fun parse(text: String): Duration {
        val input = text.trim()
        require(input.isNotEmpty()) { "Duration must not be empty" }
        if (input.startsWith("P", ignoreCase = true)) {
            return Duration.parse(input).also { require(!it.isNegative) { "Duration must not be negative" } }
        }
        val compact = input.replace(" ", "")
        var consumed = 0
        var total = Duration.ZERO
        for (match in part.findAll(compact)) {
            require(match.range.first == consumed) { "Invalid duration '$text'" }
            val amount = match.groupValues[1].toLongOrNull() ?: throw IllegalArgumentException("Duration '$text' is too large")
            total = total.plus(units.getValue(match.groupValues[2].lowercase()).multipliedBy(amount))
            consumed = match.range.last + 1
        }
        require(consumed == compact.length && consumed > 0) { "Invalid duration '$text'" }
        return total
    }

    /** Formats [duration] in the compact form accepted by [parse], for example `1h30m`. */
    @JvmStatic
    public fun format(duration: Duration): String {
        require(!duration.isNegative) { "Duration must not be negative" }
        if (duration.isZero) return "0s"
        var remaining = duration
        return buildString {
            for ((suffix, unit) in units) {
                val amount = remaining.dividedBy(unit)
                if (amount > 0) {
                    append(amount).append(suffix)
                    remaining = remaining.minus(unit.multipliedBy(amount))
                }
            }
        }
    }
}
