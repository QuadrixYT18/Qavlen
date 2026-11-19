package de.quadrixyt.qavlen.serialization

import de.quadrixyt.qavlen.config.ConfigException
import de.quadrixyt.qavlen.config.ConfigSection
import de.quadrixyt.qavlen.config.ConfigValidationException
import de.quadrixyt.qavlen.config.ConfigViolation
import de.quadrixyt.qavlen.core.backupFile
import de.quadrixyt.qavlen.core.writeAtomically
import java.io.IOException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.locks.ReentrantLock
import java.util.function.Consumer
import java.util.function.UnaryOperator
import kotlin.concurrent.withLock

/** The file was changed by someone else since it was loaded, so saving would overwrite those changes. */
public class ConfigConflictException(message: String) : ConfigException(message)

/**
 * A configuration bound to a class, typically a Kotlin data class with default values, a record or a Java bean.
 *
 * Loading reads the file, applies migrations, adds overrides, decodes and validates the value, and only then makes
 * it current. Missing values are written to the file with their defaults; existing values are never replaced.
 * Every later change follows the same order, so a broken file or an invalid update leaves the previous value active.
 *
 * Changes made with [update] stay in memory until [save]; nothing is written implicitly except missing defaults and
 * migrations while loading. [save] refuses to overwrite changes another program made to the file in the meantime.
 *
 * Instances are thread-safe; [value] always returns a complete, validated instance.
 */
public class TypedConfig<T : Any> internal constructor(
    public val type: Class<T>,
    /** The backing file, or `null` for configurations that live in memory. */
    public val file: Path?,
    public val format: ConfigFileFormat,
    public val options: TypedConfigOptions<T>,
    text: String?,
) {
    private val lock = ReentrantLock()
    private val codec = options.codec
    private val model = ConfigModel.of(type, codec)
    private val listeners = CopyOnWriteArrayList<Consumer<in T>>()
    private var persisted: Map<String, Any?>? = null
    private var stamp: FileStamp? = null

    @Volatile
    private var state: State<T>

    private class State<T>(
        val value: T,
        val root: Map<String, Any?>,
        val section: Map<String, Any?>,
        val overridden: Set<String>,
        val unknown: Set<String>,
        val migratedFrom: Int?,
    )

    init {
        state = if (file != null) {
            lock.withLock { load(file) }
        } else {
            resolve(text?.let { format.read(it, "input") } ?: emptyMap(), "input", isNew = text == null)
        }
    }

    /** The current value. */
    public val value: T get() = state.value

    /** Keys in the file that no property reads, relative to the bound section. */
    public val unknownKeys: Set<String> get() = state.unknown

    /**
     * Replaces the value with the result of [transform], typically `copy(...)` on a data class, after validating it.
     * The change stays in memory until [save]. Properties set by an override keep the override's value.
     *
     * @throws ConfigValidationException when the new value is invalid; the current value stays active
     */
    public fun update(transform: UnaryOperator<T>): T {
        val (previous, next) = lock.withLock {
            val previous = state
            val known = ConfigSection.of(ConfigBinding.toMap(transform.apply(previous.value), codec))
            val oldSection = ConfigSection.of(previous.section)
            // Overrides belong to their source, never to the file: keep what the file had at those paths.
            for (path in previous.overridden) {
                if (oldSection.contains(path)) known.set(path, oldSection.get(path)) else known.remove(path)
            }
            if (options.unknownKeys != UnknownKeys.REMOVE) previous.unknown.forEach { known.set(it, oldSection.get(it)) }
            options.version?.let { known.set(options.versionKey, it) }
            val next = resolve(withSection(previous.root, known.toMap()), "update", isNew = false)
            state = next
            previous to next
        }
        notify(previous.value, next.value)
        return next.value
    }

    /** Kotlin form of [update] with the current value as receiver: `config.update { copy(maxPlayers = 50) }`. */
    @JvmSynthetic
    public fun update(transform: T.() -> T): T = update(UnaryOperator { it.transform() })

    /** Replaces the value, validating it first. Equivalent to `update { value }`. */
    public fun set(value: T): T = update { value }

    /**
     * Writes the current value to the file.
     *
     * @param overwriteExternalChanges write even when the file was changed outside this instance since it was loaded
     * @return `false` when the file already had this content
     * @throws ConfigConflictException when the file changed on disk and [overwriteExternalChanges] is `false`
     */
    @JvmOverloads
    public fun save(overwriteExternalChanges: Boolean = false): Boolean {
        val target = file ?: throw ConfigException("This configuration has no file; use encode() to get its text")
        return lock.withLock {
            if (!overwriteExternalChanges && FileStamp.of(target) != stamp) {
                throw ConfigConflictException(
                    "$target was changed outside this configuration since it was loaded; " +
                        "call reload() to take over those changes or save(true) to replace them",
                )
            }
            val current = state
            if (current.root == persisted && Files.exists(target)) return@withLock false
            write(target, current.root)
            true
        }
    }

    /**
     * Reads the file again. On any failure a [ConfigException] is thrown and the current value stays active.
     *
     * @return the new value
     */
    public fun reload(): T {
        val target = file ?: throw ConfigException("Cannot reload a configuration without a file")
        val (previous, next) = lock.withLock {
            val previous = state
            val next = load(target)
            state = next
            previous to next
        }
        notify(previous.value, next.value)
        return next.value
    }

    /** Reloads only when the file's modification time or size changed. Returns `true` when it reloaded. */
    public fun reloadIfModified(): Boolean {
        val target = file ?: throw ConfigException("Cannot reload a configuration without a file")
        if (lock.withLock { FileStamp.of(target) == stamp }) return false
        reload()
        return true
    }

    /** The current content in this configuration's format, including comments for YAML. */
    public fun encode(): String = format.write(state.root, model.takeIf { options.prefix == null })

    /** Calls [listener] with the new value after every update or reload that changed it. Closing the handle removes it. */
    public fun addListener(listener: Consumer<in T>): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun load(target: Path): State<T> {
        val before = FileStamp.of(target)
        val text = try {
            if (Files.exists(target)) Files.readString(target) else null
        } catch (exception: IOException) {
            throw ConfigException("Could not read $target", exception)
        }
        val parsed = text?.let { format.read(it, target.toString()) } ?: emptyMap()
        val resolved = resolve(parsed, target.toString(), isNew = text == null)
        val changed = text == null || resolved.root != parsed
        val wanted = options.writeDefaults ||
            resolved.migratedFrom != null ||
            options.unknownKeys == UnknownKeys.REMOVE &&
            resolved.unknown.isNotEmpty()
        if (changed && wanted) {
            if (resolved.migratedFrom != null && options.backups && text != null) {
                try {
                    backupFile(target, "v${resolved.migratedFrom}")
                } catch (exception: IOException) {
                    throw ConfigException("Could not back up $target before migrating it", exception)
                }
            }
            write(target, resolved.root)
        } else {
            persisted = parsed
            stamp = before
        }
        if (options.unknownKeys == UnknownKeys.WARN && resolved.unknown.isNotEmpty()) {
            System.getLogger(TypedConfig::class.java.name)
                .log(System.Logger.Level.WARNING, "Unknown keys in $target: ${resolved.unknown.joinToString()}")
        }
        return resolved
    }

    /** Runs the pipeline on [root] without changing any state. */
    private fun resolve(root: Map<String, Any?>, source: String, isNew: Boolean): State<T> {
        val section = ConfigSection.of(section(root, source))
        val migratedFrom = migrate(section, isNew, source)
        val overrides = linkedMapOf<String, Any?>()
        val paths = model?.scalarPaths().orEmpty()
        options.sources.forEach { overrides.putAll(it.values(paths)) }
        val effective = ConfigSection.of(section.toMap()).also { tree -> overrides.forEach { (path, value) -> tree.set(path, value) } }
        val value = decode(effective.toMap(), source)
        validate(value, source)

        // Defaults come from the file's own values, so overrides never end up in the file.
        val fileValue = if (overrides.isEmpty()) value else runCatching { codec.convert(section.toMap(), type) }.getOrNull()
        val known = fileValue?.let { ConfigBinding.toMap(it, codec) }
        val unknown = if (known == null) {
            emptySet()
        } else {
            section.keys(true).filterTo(sortedSetOf()) {
                !covered(known, it) && it != options.versionKey.takeIf { options.version != null }
            }
        }
        val output = ConfigSection.of(section.toMap())
        if (options.writeDefaults && known != null) addMissing(output, known, "")
        if (options.unknownKeys == UnknownKeys.REMOVE) unknown.forEach(output::remove)
        val outputTree = output.toMap()
        return State(value, withSection(root, outputTree), outputTree, overrides.keys.toSet(), unknown, migratedFrom)
    }

    @Suppress("UNCHECKED_CAST")
    private fun section(root: Map<String, Any?>, source: String): Map<String, Any?> {
        val prefix = options.prefix ?: return root
        return when (val value = ConfigSection.of(root).get(prefix)) {
            null -> emptyMap()
            is Map<*, *> -> value as Map<String, Any?>
            else -> throw ConfigException("'$prefix' in $source must be a section")
        }
    }

    private fun withSection(root: Map<String, Any?>, section: Map<String, Any?>): Map<String, Any?> {
        val prefix = options.prefix ?: return section
        return ConfigSection.of(root).also { it.set(prefix, section) }.toMap()
    }

    private fun migrate(section: ConfigSection, isNew: Boolean, source: String): Int? {
        val current = options.version ?: return null
        if (isNew) {
            section.set(options.versionKey, current)
            return null
        }
        val stored = when (val value = section.get(options.versionKey)) {
            null -> 1
            is Int -> value
            else -> throw ConfigException("'${qualify(options.versionKey)}' in $source must be an integer, was $value")
        }
        if (stored > current) throw ConfigException("$source has version $stored, but only versions up to $current are supported")
        if (stored == current) return null
        for (migration in options.migrations.filter { it.fromVersion >= stored }) {
            try {
                migration.migrate(section)
            } catch (exception: RuntimeException) {
                throw ConfigException("Migration from version ${migration.fromVersion} failed for $source", exception)
            }
        }
        section.set(options.versionKey, current)
        return stored
    }

    private fun decode(tree: Map<String, Any?>, source: String): T = try {
        codec.convert(tree, type)
    } catch (exception: SerializationException) {
        val path = exception.path?.let(::qualify)
        val reason = (exception.cause as? tools.jackson.core.JacksonException)?.originalMessage ?: exception.message
        throw ConfigException("Invalid configuration in $source${path?.let { " at '$it'" }.orEmpty()}: $reason", exception)
    }

    private fun validate(value: T, source: String) {
        val violations = mutableListOf<ConfigViolation>()
        model?.validate(ConfigBinding.toMap(value, codec), "", violations)
        options.validators.forEach { violations += it.validate(value) }
        if (violations.isNotEmpty()) {
            throw ConfigValidationException(violations.map { ConfigViolation(qualify(it.path), it.message) })
                .also { it.addSuppressed(ConfigException("Configuration source: $source")) }
        }
    }

    private fun write(target: Path, root: Map<String, Any?>) {
        try {
            writeAtomically(target, format.write(root, model.takeIf { options.prefix == null }))
        } catch (exception: IOException) {
            throw ConfigException("Could not write $target", exception)
        }
        persisted = root
        stamp = FileStamp.of(target)
    }

    private fun notify(previous: T, next: T) {
        if (previous == next) return
        for (listener in listeners) {
            try {
                listener.accept(next)
            } catch (exception: RuntimeException) {
                System.getLogger(TypedConfig::class.java.name).log(System.Logger.Level.ERROR, "A configuration listener failed", exception)
            }
        }
    }

    private fun qualify(path: String) = options.prefix?.let { if (path.isEmpty()) it else "$it.$path" } ?: path

    private data class FileStamp(val modified: FileTime, val size: Long) {
        companion object {
            fun of(path: Path): FileStamp? = try {
                FileStamp(Files.getLastModifiedTime(path), Files.size(path))
            } catch (_: IOException) {
                null
            }
        }
    }

    override fun toString(): String = "TypedConfig(${type.simpleName}, ${file ?: "memory"})"

    private companion object {
        /** `true` when [path] exists in [known] or lies inside a value that is not a section, such as a map property. */
        fun covered(known: Map<String, Any?>, path: String): Boolean {
            var node: Any? = known
            for (segment in path.split('.')) {
                if (node !is Map<*, *>) return true
                if (!node.containsKey(segment)) return false
                node = node[segment]
            }
            return true
        }

        fun addMissing(target: ConfigSection, known: Map<String, Any?>, prefix: String) {
            for ((key, value) in known) {
                val path = if (prefix.isEmpty()) key else "$prefix.$key"
                when {
                    !target.contains(path) -> target.set(path, value)
                    value is Map<*, *> && target.get(path) is Map<*, *> -> {
                        @Suppress("UNCHECKED_CAST")
                        addMissing(target, value as Map<String, Any?>, path)
                    }
                }
            }
        }
    }
}
