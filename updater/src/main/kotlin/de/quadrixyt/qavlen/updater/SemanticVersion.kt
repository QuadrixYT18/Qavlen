package de.quadrixyt.qavlen.updater

import java.math.BigInteger

/**
 * A version number such as `1.2.0`, `v1.10`, `1.2.0-beta.1`, `1.2.0-rc.2` or `1.2.0-SNAPSHOT`, compared by its
 * numbers rather than as text, so `1.10.0` is newer than `1.9.0`.
 *
 * Rules:
 * - A leading `v` is ignored, and so is build metadata after `+` (`1.0.0+1234`).
 * - The numbers before the first label are compared one by one; missing numbers count as `0`, so `1.2` equals `1.2.0`.
 * - Labels after the numbers are pre-release markers when they are one of `snapshot`, `dev`, `nightly` (lowest),
 *   `alpha` or `a`, `beta` or `b`, `pre` or `preview`, `rc` or `cr` (highest), optionally followed by a number:
 *   `1.2.0-SNAPSHOT` < `1.2.0-alpha.1` < `1.2.0-beta.1` < `1.2.0-beta.2` < `1.2.0-rc.1` < `1.2.0`.
 * - Other labels, such as a platform suffix in `5.5.71-velocity` or `2.0.0-paper`, do not change the order:
 *   `5.5.71-velocity` equals `5.5.71`.
 * - A version that does not start with a number, such as `latest` or a commit hash, cannot be parsed and is not
 *   comparable with anything.
 */
public class SemanticVersion private constructor(
    /** The text as given. */
    public val text: String,
    private val numbers: List<BigInteger>,
    private val markers: List<Marker>,
) {
    /** `true` when the version has a pre-release marker such as `beta` or `SNAPSHOT`. */
    public val isPrerelease: Boolean get() = markers.isNotEmpty()

    /** The channel the pre-release markers indicate: [UpdateChannel.STABLE] without markers. */
    public val channel: UpdateChannel get() = when {
        markers.isEmpty() -> UpdateChannel.STABLE
        markers.first().rank <= ALPHA -> UpdateChannel.ALPHA
        else -> UpdateChannel.BETA
    }

    private class Marker(val rank: Int, val number: BigInteger)

    /** Negative when this version is older than [other], zero when equal and positive when newer. */
    public fun compareTo(other: SemanticVersion): Int {
        for (index in 0 until maxOf(numbers.size, other.numbers.size)) {
            val compared = (numbers.getOrNull(index) ?: BigInteger.ZERO).compareTo(other.numbers.getOrNull(index) ?: BigInteger.ZERO)
            if (compared != 0) return compared
        }
        for (index in 0 until maxOf(markers.size, other.markers.size)) {
            // A missing marker means a release, which is newer than any pre-release of the same numbers.
            val mine = markers.getOrNull(index) ?: return 1
            val theirs = other.markers.getOrNull(index) ?: return -1
            val compared = compareValuesBy(mine, theirs, Marker::rank, Marker::number)
            if (compared != 0) return compared
        }
        return 0
    }

    override fun equals(other: Any?): Boolean = other is SemanticVersion && compareTo(other) == 0

    override fun hashCode(): Int =
        numbers.dropLastWhile { it.signum() == 0 }.hashCode() * 31 + markers.map { it.rank to it.number }.hashCode()

    override fun toString(): String = text

    public companion object {
        private const val ALPHA = 1
        private val ranks = mapOf(
            "snapshot" to 0, "dev" to 0, "nightly" to 0,
            "alpha" to ALPHA, "a" to ALPHA,
            "beta" to 2, "b" to 2,
            "pre" to 3, "preview" to 3,
            "rc" to 4, "cr" to 4,
        )
        private val core = Regex("^(\\d+(?:\\.\\d+)*)")
        private val identifier = Regex("[a-z]+|\\d+")

        /** Parses [text], or returns `null` when it does not start with a number (after an optional `v`). */
        @JvmStatic
        public fun parse(text: String): SemanticVersion? {
            val trimmed = text.trim().removePrefix("v").removePrefix("V").substringBefore('+')
            val numbers = core.find(trimmed)?.value ?: return null
            val parts = identifier.findAll(trimmed.substring(numbers.length).lowercase()).map { it.value }.toList()
            val markers = mutableListOf<Marker>()
            parts.forEachIndexed { index, part ->
                val rank = ranks[part] ?: return@forEachIndexed
                val number = parts.getOrNull(index + 1)?.toBigIntegerOrNull() ?: BigInteger.ZERO
                markers += Marker(rank, number)
            }
            return SemanticVersion(text, numbers.split('.').map(::BigInteger), markers)
        }

        /**
         * Compares two version texts: negative when [version] is older than [other], zero when equal, positive when
         * newer, and `null` when either cannot be parsed.
         */
        @JvmStatic
        public fun compare(version: String, other: String): Int? {
            val first = parse(version) ?: return null
            val second = parse(other) ?: return null
            return first.compareTo(second)
        }
    }
}
