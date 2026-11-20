package de.quadrixyt.qavlen.config

import de.quadrixyt.qavlen.language.MessageTemplate
import net.kyori.adventure.text.Component
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicLong

/**
 * Parsed messages of one [ConfigFile], so frequently sent messages are not parsed again on every call. A message
 * requested without placeholder values is kept as its finished component.
 *
 * Every change of the configuration, through `set`, `remove` or a reload, starts a new revision and makes all entries
 * stale. Before a lookup, the file is checked for edits at most once per [WATCH_INTERVAL_MILLIS]; an edited file is
 * reloaded, unless the configuration has unsaved changes, which must not be lost.
 */
internal class MessageCache(private val owner: ConfigFile) {
    private class Entry(val revision: Long, val template: MessageTemplate, val prefix: Component?, val fixed: Component)

    private val entries = ConcurrentHashMap<String, Entry>()
    private val revision = AtomicLong()

    @Volatile
    private var nextCheck = 0L

    /** Makes every entry stale; called after each change of the configuration's values. */
    fun invalidate() {
        revision.incrementAndGet()
        entries.clear()
    }

    fun component(section: ConfigSection, path: String, placeholders: Map<String, Any?>): Component? {
        watch()
        val key = qualified(section, path)
        // The revision is read before the values, so an entry built from values that change meanwhile is never reused.
        val current = revision.get()
        val entry = entries[key]?.takeIf { it.revision == current }
            ?: create(section, path, current)?.also { entries[key] = it }
            ?: return null
        // Without values, every message renders the same, also one that uses <name> placeholders.
        if (placeholders.isEmpty()) return entry.fixed
        return invalidMessage(section, path) { MessageComponents.render(entry.template, placeholders, entry.prefix) }
    }

    private fun create(section: ConfigSection, path: String, revision: Long): Entry? {
        val text = section.getString(path) ?: return null
        return invalidMessage(section, path) {
            val template = MessageTemplate.parse(text)
            val prefix = messagePrefix(section, path, text)
            Entry(revision, template, prefix, MessageComponents.render(template, emptyMap(), prefix))
        }
    }

    private fun watch() {
        val now = System.nanoTime()
        if (now - nextCheck < 0) return
        nextCheck = now + TimeUnit.MILLISECONDS.toNanos(WATCH_INTERVAL_MILLIS)
        owner.reloadIfEditedOnDisk()
    }

    companion object {
        /** How often at most a cache lookup checks whether the file was edited. */
        const val WATCH_INTERVAL_MILLIS: Long = 1000
    }
}

/** The cache of the file [section] belongs to, or `null` for sections without a file. */
internal fun messageCache(section: ConfigSection): MessageCache? = when (section) {
    is ConfigFile -> section.messages
    is NodeSection -> section.messages
    else -> null
}

/** Renders the message at [path] without a cache, or returns `null` when it is missing. */
internal fun renderMessage(section: ConfigSection, path: String, placeholders: Map<String, Any?>): Component? {
    val text = section.getString(path) ?: return null
    return invalidMessage(section, path) { MessageComponents.render(text, placeholders, messagePrefix(section, path, text)) }
}

/**
 * Renders the nearest `prefix` for the message at [path], searched from the message's section up to the root, when
 * [text] uses `<prefix>`. A missing prefix renders as nothing.
 */
internal fun messagePrefix(section: ConfigSection, path: String, text: String): Component? {
    if ("<prefix>" !in text && "{prefix}" !in text) return null
    val parent = path.substringBeforeLast('.', "")
    val candidates = generateSequence(parent) { if (it.isEmpty()) null else it.substringBeforeLast('.', "") }
        .map { if (it.isEmpty()) MESSAGE_PREFIX_KEY else "$it.$MESSAGE_PREFIX_KEY" }
    val prefix = candidates.firstNotNullOfOrNull { section.get(it) as? String } ?: (section as? NodeSection)?.enclosingPrefix()
    return prefix?.let { MessageComponents.render(it) } ?: Component.empty()
}

/** Runs [render] and reports malformed placeholders or unsuitable values with the message's full path. */
internal inline fun <R> invalidMessage(section: ConfigSection, path: String, render: () -> R): R = try {
    render()
} catch (failure: IllegalArgumentException) {
    throw ConfigException("Invalid message at '${qualified(section, path)}': ${failure.message}", failure)
}

internal fun qualified(section: ConfigSection, path: String): String = if (section.path.isEmpty()) path else "${section.path}.$path"

/** The key `<prefix>` in messages refers to. */
internal const val MESSAGE_PREFIX_KEY: String = "prefix"
