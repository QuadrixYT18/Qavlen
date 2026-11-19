package de.quadrixyt.qavlen.language

import java.nio.file.Path
import java.text.ChoiceFormat
import java.time.ZoneId
import java.util.Locale
import java.util.ResourceBundle

/**
 * Translated messages for several locales and namespaces.
 *
 * Keys are written as `key` for the default namespace or `namespace:key`. A lookup for `de_AT` tries `de_AT`,
 * `de`, then the default locale's candidates and finally the root locale. Sources added later override keys of
 * earlier sources, so bundled defaults can be overridden by files in a plugin's data folder.
 *
 * Instances are thread-safe. [reload] swaps all catalogs atomically and keeps the previous ones on failure.
 */
public class Translations private constructor(
    public val defaultLocale: Locale,
    public val zone: ZoneId,
    private val sources: List<TranslationSource>,
) {
    @Volatile
    private var catalogs: Map<Locale, Map<String, MessageTemplate>> = load()

    /** Locales that have at least one translation. */
    public val locales: Set<Locale> get() = catalogs.keys

    /** Re-reads every source. Throws [TranslationException] and keeps the current translations on failure. */
    public fun reload() {
        catalogs = load()
    }

    /** Returns the best matching template for [key], or `null` when no candidate locale defines it. */
    public fun template(locale: Locale, key: String): MessageTemplate? {
        val current = catalogs
        val qualified = qualify(key)
        return candidates(locale).firstNotNullOfOrNull { current[it]?.get(qualified) }
    }

    /** `true` when [key] resolves for [locale] through the fallback chain. */
    public fun contains(locale: Locale, key: String): Boolean = template(locale, key) != null

    /** Formats [key] for [locale]. A missing key yields the key itself so it is visible instead of silently empty. */
    public fun format(locale: Locale, key: String, vararg placeholders: Placeholder): String =
        format(locale, key, placeholders.associate { it.name to it.value })

    /** Formats [key] for [locale] using placeholder values by name. */
    public fun format(locale: Locale, key: String, placeholders: Map<String, Any?>): String {
        val template = template(locale, key) ?: return key
        return render(template, locale, placeholders)
    }

    /** Renders an already resolved template. */
    public fun render(template: MessageTemplate, locale: Locale, placeholders: Map<String, Any?>): String = buildString {
        for (segment in template.segments) {
            when (segment) {
                is MessageTemplate.Segment.Text -> append(segment.text)
                is MessageTemplate.Segment.Placeholder -> append(formatPlaceholder(segment, locale, placeholders))
            }
        }
    }

    /**
     * Formats a single placeholder. Unknown placeholders are kept as `{name}`. Platform adapters use this to
     * insert values as literal text into rich-text formats.
     */
    public fun formatPlaceholder(
        segment: MessageTemplate.Segment.Placeholder,
        locale: Locale,
        placeholders: Map<String, Any?>,
    ): String {
        if (!placeholders.containsKey(segment.name)) return "{${segment.name}}"
        val value = placeholders[segment.name]
        val style = segment.style
        if (style != null && style.startsWith("choice:")) {
            val number = value as? Number ?: throw IllegalArgumentException("'${segment.name}' must be a number for choice formatting")
            val chosen = ChoiceFormat(style.removePrefix("choice:")).format(number.toDouble())
            return render(MessageTemplate.parse(chosen), locale, placeholders)
        }
        return ValueFormatter.format(value, style, locale, zone)
    }

    /** All translations of [key] that are defined directly for a locale, without fallbacks. */
    public fun localizations(key: String): Map<Locale, String> {
        val qualified = qualify(key)
        return catalogs.mapNotNull { (locale, messages) -> messages[qualified]?.let { locale to it.source } }.toMap()
    }

    /** Keys defined for [locale] itself, or for the default locale when [locale] is `null`. */
    @JvmOverloads
    public fun keys(locale: Locale? = null): Set<String> = catalogs[locale ?: defaultLocale]?.keys.orEmpty()

    /** Keys of the default locale that [locale] cannot resolve without falling back to another language. */
    public fun missingKeys(locale: Locale): Set<String> {
        val current = catalogs
        val own = ownCandidates(locale).mapNotNull { current[it] }
        return keys(defaultLocale).filterTo(sortedSetOf()) { key -> own.none { key in it } }
    }

    /** `true` when [locale] or its language defines [key] itself, without falling back to another language. */
    public fun isTranslated(locale: Locale, key: String): Boolean {
        val current = catalogs
        val qualified = qualify(key)
        return ownCandidates(locale).any { current[it]?.containsKey(qualified) == true }
    }

    /**
     * Reports, for every locale other than the default: missing translations, keys the default locale does not
     * define (usually typos or leftovers), and placeholders the default text does not use.
     */
    public fun validate(): List<TranslationIssue> {
        val current = catalogs
        val reference = current[defaultLocale].orEmpty()
        return current.keys.filter { it != defaultLocale }.sortedBy(Locale::toLanguageTag).flatMap { locale ->
            val missing = missingKeys(locale).map { TranslationIssue(locale, it, "missing translation") }
            val messages = current.getValue(locale)
            val extra = messages.keys.filter { it !in reference }.sorted()
                .map { TranslationIssue(locale, it, "not defined in the default locale") }
            val mismatched = messages.mapNotNull { (key, template) ->
                val expected = reference[key]?.placeholderNames ?: return@mapNotNull null
                val unknown = template.placeholderNames - expected
                if (unknown.isEmpty()) null else TranslationIssue(locale, key, "unknown placeholders $unknown")
            }
            missing + extra + mismatched
        }
    }

    /** Like [validate], and additionally reports [requiredKeys] that the default locale does not define. */
    public fun validate(requiredKeys: Collection<String>): List<TranslationIssue> {
        val defined = catalogs[defaultLocale].orEmpty()
        val missing = requiredKeys.map(::qualify).filter { it !in defined }.distinct()
            .map { TranslationIssue(defaultLocale, it, "required key is missing") }
        return missing + validate()
    }

    private fun candidates(locale: Locale): List<Locale> =
        (ownCandidates(locale) + ownCandidates(defaultLocale) + Locale.ROOT).distinct()

    private fun ownCandidates(locale: Locale): List<Locale> =
        control.getCandidateLocales("", locale).filter { it != Locale.ROOT }

    private fun load(): Map<Locale, Map<String, MessageTemplate>> {
        val merged = linkedMapOf<Locale, MutableMap<String, MessageTemplate>>()
        for (source in sources) {
            for (catalog in source.load()) {
                val target = merged.getOrPut(catalog.locale) { linkedMapOf() }
                catalog.messages.forEach { (key, text) ->
                    val qualified = if (catalog.namespace.isEmpty()) key else "${catalog.namespace}:$key"
                    target[qualified] = try {
                        MessageTemplate.parse(text)
                    } catch (exception: IllegalArgumentException) {
                        throw TranslationException("${catalog.origin}: '$key': ${exception.message}", exception)
                    }
                }
            }
        }
        return merged.mapValues { (_, messages) -> messages.toMap() }
    }

    /** Builds [Translations]. Sources are read when [build] is called. */
    public class Builder internal constructor() {
        private var defaultLocale: Locale = Locale.ENGLISH
        private var zone: ZoneId = ZoneId.systemDefault()
        private val sources = mutableListOf<TranslationSource>()

        public fun defaultLocale(locale: Locale): Builder = apply { defaultLocale = locale }

        /** Time zone used for [java.time.Instant] and [java.util.Date] placeholders. */
        public fun zone(zone: ZoneId): Builder = apply { this.zone = zone }

        /**
         * Reads `<locale>.yml|json|properties` files in [directory] into the default namespace and files in
         * subdirectories into a namespace named after the subdirectory, for example `lang/qavlen/de.yml`.
         * A missing directory contributes nothing.
         */
        public fun directory(directory: Path): Builder = apply { sources += DirectorySource(directory) }

        /**
         * Reads bundled resources `<path>/<locale>.yml|json|properties` for each locale. Resources cannot be
         * listed portably, so locales are given explicitly; a missing resource is an error.
         */
        @JvmOverloads
        public fun resources(loader: ClassLoader, path: String, locales: List<Locale>, namespace: String = ""): Builder = apply {
            sources += ResourceSource(loader, path.trim('/'), locales, namespace)
        }

        /**
         * Copies bundled `<path>/<locale>.yml|json` resources to [directory] as files server owners can edit, for
         * example `plugins/MyPlugin/lang/de.yml`. On every build and [reload], keys added in a new version are
         * written to the files while existing texts are kept; files are never replaced as a whole.
         */
        @JvmOverloads
        public fun editableResources(
            loader: ClassLoader,
            path: String,
            locales: List<Locale>,
            directory: Path,
            namespace: String = "",
        ): Builder = apply { sources += EditableSource(loader, path.trim('/'), locales, directory, namespace) }

        /** Adds messages from memory. */
        @JvmOverloads
        public fun messages(locale: Locale, messages: Map<String, String>, namespace: String = ""): Builder = apply {
            sources += TranslationSource { listOf(Catalog(namespace, locale, messages.toMap(), "messages for $locale")) }
        }

        public fun build(): Translations = Translations(defaultLocale, zone, sources.toList())
    }

    public companion object {
        private val control = ResourceBundle.Control.getNoFallbackControl(ResourceBundle.Control.FORMAT_DEFAULT)

        @JvmStatic
        public fun builder(): Builder = Builder()

        private fun qualify(key: String): String = if (key.startsWith(':')) key.substring(1) else key
    }
}

/** A problem found by [Translations.validate]. */
public data class TranslationIssue(val locale: Locale, val key: String, val message: String) {
    override fun toString(): String = "[${locale.toLanguageTag()}] $key: $message"
}

/** Translation files could not be read or contain malformed templates. */
public class TranslationException(message: String, cause: Throwable? = null) : RuntimeException(message, cause)
