package de.quadrixyt.qavlen.updater

/** Release channels, from the most to the least stable. A channel includes every more stable one. */
public enum class UpdateChannel {
    /** Only stable releases. The default. */
    STABLE,

    /** Stable releases and betas, including release candidates and pre-releases. */
    BETA,

    /** Every published version, including alphas and snapshots. */
    ALPHA,

    ;

    /** `true` when a release of [channel] belongs to this channel. */
    public fun includes(channel: UpdateChannel): Boolean = channel.ordinal <= ordinal
}
