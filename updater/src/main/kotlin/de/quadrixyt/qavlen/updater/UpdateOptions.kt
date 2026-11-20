package de.quadrixyt.qavlen.updater

import java.time.Duration
import java.util.function.Supplier

/**
 * Settings of an [UpdateChecker]. The defaults suit most plugins: stable releases only, every platform and Minecraft
 * version, a cache of 30 minutes and a timeout of 10 seconds.
 */
public class UpdateOptions private constructor(builder: Builder) {
    public val channel: UpdateChannel = builder.channel

    /** Only versions published for this Minecraft version (on Hangar: the platform version), or `null` for any. */
    public val minecraftVersion: String? = builder.minecraftVersion

    /** Only versions for this platform, such as `paper` or `velocity`, or `null` for any. */
    public val platform: String? = builder.platform

    /** How long a successful result is reused before the provider is asked again. */
    public val cacheDuration: Duration = builder.cacheDuration

    /** Time limit for each request. */
    public val timeout: Duration = builder.timeout

    /** `User-Agent` sent to the provider, or `null` for one naming Qavlen and the project. */
    public val userAgent: String? = builder.userAgent

    internal val githubToken: Supplier<String?>? = builder.githubToken

    public class Builder internal constructor() {
        internal var channel = UpdateChannel.STABLE
        internal var minecraftVersion: String? = null
        internal var platform: String? = null
        internal var cacheDuration: Duration = Duration.ofMinutes(30)
        internal var timeout: Duration = Duration.ofSeconds(10)
        internal var userAgent: String? = null
        internal var githubToken: Supplier<String?>? = null

        /** Which releases count; [UpdateChannel.STABLE] by default. */
        public fun channel(channel: UpdateChannel): Builder = apply { this.channel = channel }

        /** Only considers versions published for [version], for example `26.3`. */
        public fun minecraftVersion(version: String): Builder = apply {
            require(version.isNotBlank()) { "The Minecraft version must not be blank" }
            minecraftVersion = version.trim()
        }

        /**
         * Only considers versions for [platform]: `paper`, `purpur`, `folia`, `spigot`, `bukkit`, `velocity`,
         * `waterfall` or `bungeecord`. Paper also accepts versions published for Spigot and Bukkit.
         */
        public fun platform(platform: String): Builder = apply {
            require(platform.isNotBlank()) { "The platform must not be blank" }
            this.platform = platform.trim().lowercase()
        }

        /** How long a successful result is reused; [Duration.ZERO] asks the provider on every check. */
        public fun cacheDuration(duration: Duration): Builder = apply {
            require(!duration.isNegative) { "The cache duration must not be negative" }
            cacheDuration = duration
        }

        public fun timeout(timeout: Duration): Builder = apply {
            require(!timeout.isNegative && !timeout.isZero) { "The timeout must be positive" }
            this.timeout = timeout
        }

        /** Identifies your plugin to the provider, for example `MyPlugin/1.0 (contact@example.com)`. */
        public fun userAgent(userAgent: String): Builder = apply {
            require(userAgent.isNotBlank()) { "The user agent must not be blank" }
            this.userAgent = userAgent
        }

        /**
         * A GitHub token for higher rate limits or private repositories. It is read for each request and never
         * stored, logged or included in results; return `null` to send no token.
         */
        public fun githubToken(token: Supplier<String?>): Builder = apply { githubToken = token }

        public fun build(): UpdateOptions = UpdateOptions(this)
    }

    public companion object {
        @JvmStatic
        public fun builder(): Builder = Builder()

        @JvmStatic
        public fun defaults(): UpdateOptions = Builder().build()
    }
}
