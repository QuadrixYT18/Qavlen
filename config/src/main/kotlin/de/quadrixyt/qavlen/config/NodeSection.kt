package de.quadrixyt.qavlen.config

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/** [ConfigSection] over a mutable tree. [root] is re-read on every access so a reload swaps all views at once. */
internal class NodeSection(
    private val lock: ReentrantReadWriteLock,
    override val path: String,
    private val changed: ((String) -> Unit)? = null,
    private val root: () -> Node,
) : ConfigSection {
    private val prefix = if (path.isEmpty()) emptyList() else segments(path)

    override fun contains(path: String): Boolean = lock.read { lookup(root(), absolute(path)) !== Missing }

    override fun get(path: String): Any? = lock.read {
        lookup(root(), absolute(path)).let { if (it === Missing) null else normalize(it) }
    }

    override fun set(path: String, value: Any?) {
        val copy = normalize(value)
        val absolute = absolute(path)
        val modified = lock.write {
            val previous = lookup(root(), absolute)
            put(root(), absolute, copy)
            previous != copy
        }
        // Listeners run after the lock is released, so they may read or write the configuration themselves.
        if (modified) changed?.invoke(absolute.joinToString("."))
    }

    override fun remove(path: String): Boolean {
        val absolute = absolute(path)
        val removed = lock.write { delete(root(), absolute) }
        if (removed) changed?.invoke(absolute.joinToString("."))
        return removed
    }

    override fun keys(deep: Boolean): Set<String> = lock.read {
        val node = own() ?: return@read emptySet()
        if (deep) leafPaths(node) else node.keys.toSet()
    }

    override fun toMap(): Map<String, Any?> = lock.read { own()?.let(::deepCopy) ?: emptyMap() }

    override fun getSection(path: String): ConfigSection? = lock.read {
        val absolute = absolute(path)
        if (lookup(root(), absolute) is Map<*, *>) NodeSection(lock, absolute.joinToString("."), changed, root) else null
    }

    fun enclosingPrefix(): String? = lock.read {
        (prefix.size - 1 downTo 0).firstNotNullOfOrNull { size -> lookup(root(), prefix.take(size) + MESSAGE_PREFIX_KEY) as? String }
    }

    @Suppress("UNCHECKED_CAST")
    private fun own(): Map<String, Any?>? = if (prefix.isEmpty()) root() else lookup(root(), prefix) as? Map<String, Any?>

    private fun absolute(relative: String): List<String> = prefix + segments(relative)

    override fun toString(): String = "ConfigSection(path='$path')"
}

internal fun detachedSection(root: Node): ConfigSection = NodeSection(ReentrantReadWriteLock(), "") { root }
