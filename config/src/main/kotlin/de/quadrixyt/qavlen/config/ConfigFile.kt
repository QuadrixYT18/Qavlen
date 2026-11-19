package de.quadrixyt.qavlen.config

import de.quadrixyt.qavlen.core.writeAtomically
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter
import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

/**
 * A YAML or JSON configuration document, optionally backed by a file.
 *
 * Loading and [reload] parse, migrate, merge defaults and validate a detached candidate first. The current
 * values are only replaced when every step succeeded, and a file that cannot be parsed is never overwritten.
 * [save] writes atomically and skips the write when the serialized content did not change.
 *
 * Comments are not preserved: the underlying parsers do not expose them, so files rewritten by Qavlen lose them.
 *
 * All operations are thread-safe.
 */
public sealed class ConfigFile(
    /** Backing file, or `null` for documents read from a stream. */
    public val file: Path?,
    public val format: ConfigFormat,
    public val options: ConfigOptions,
) : ConfigSection {
    private val lock = ReentrantReadWriteLock()
    private var root: Node = linkedMapOf()
    private var persisted: String? = null
    private val view = NodeSection(lock, "") { root }

    override val path: String get() = ""

    override fun contains(path: String): Boolean = view.contains(path)

    override fun get(path: String): Any? = view.get(path)

    override fun set(path: String, value: Any?): Unit = view.set(path, value)

    override fun remove(path: String): Boolean = view.remove(path)

    override fun keys(deep: Boolean): Set<String> = view.keys(deep)

    override fun toMap(): Map<String, Any?> = view.toMap()

    override fun getSection(path: String): ConfigSection? = view.getSection(path)

    /** `true` when the current values differ from what was last read from or written to the file. */
    public val isDirty: Boolean get() = lock.read { format.encode(root) != persisted }

    /**
     * Re-reads the backing file. On any failure a [ConfigException] is thrown and the previous values remain.
     *
     * @return the paths of defaults that were added to the file
     */
    public fun reload(): List<String> {
        val target = file ?: throw ConfigException("Cannot reload a configuration that was not loaded from a file")
        return lock.write {
            val text = try {
                if (Files.exists(target)) Files.readString(target) else null
            } catch (exception: IOException) {
                throw ConfigException("Could not read $target", exception)
            }
            apply(text, target.toString())
        }
    }

    /**
     * Validates and writes the current values to the backing file.
     *
     * @return `false` when the file already contained the same content and nothing was written
     */
    public fun save(): Boolean {
        val target = file ?: throw ConfigException("This configuration has no backing file; use saveTo(path)")
        return lock.write {
            validate(root)
            val text = format.encode(root)
            if (text == persisted && Files.exists(target)) return@write false
            write(target, text)
            persisted = text
            true
        }
    }

    /** Validates and writes the current values to [target] without changing the backing file. */
    public fun saveTo(target: Path): Unit = lock.read {
        validate(root)
        write(target, format.encode(root))
    }

    /** Serializes the current values to [writer]. The writer is not closed. */
    public fun writeTo(writer: Writer): Unit = lock.read { writer.write(format.encode(root)) }

    /** The current values serialized in this document's format. */
    public fun saveToString(): String = lock.read { format.encode(root) }

    internal fun initialize(text: String?, source: String) = lock.write { apply(text, source) }

    private fun apply(text: String?, source: String): List<String> {
        val candidate = text?.let { format.decode(it, source) } ?: linkedMapOf()
        val loadedText = text?.let { format.encode(candidate) }
        val migratedFrom = migrate(candidate, isNew = text == null, source)
        val added = mergeDefaults(candidate, options.defaults)
        validate(candidate)

        val target = file
        val encoded = format.encode(candidate)
        val changed = encoded != loadedText
        if (target != null && changed && options.writeDefaults) {
            if (migratedFrom != null && options.backups && text != null) backup(target, migratedFrom)
            write(target, encoded)
            persisted = encoded
        } else {
            persisted = loadedText ?: if (target == null) encoded else null
        }
        root = candidate
        return added
    }

    /** Runs pending migrations and returns the original version when anything ran. */
    private fun migrate(candidate: Node, isNew: Boolean, source: String): Int? {
        val current = options.version ?: return null
        val key = segments(options.versionKey)
        if (isNew) {
            put(candidate, key, current)
            return null
        }
        val stored = when (val value = lookup(candidate, key)) {
            Missing, null -> 1
            is Int -> value
            else -> throw ConfigException("'${options.versionKey}' in $source must be an integer, was $value")
        }
        if (stored > current) {
            throw ConfigException("$source has version $stored, but only versions up to $current are supported")
        }
        if (stored == current) return null
        val section = detachedSection(candidate)
        for (migration in options.migrations.filter { it.fromVersion >= stored }) {
            try {
                migration.migrate(section)
            } catch (exception: RuntimeException) {
                throw ConfigException("Migration from version ${migration.fromVersion} failed for $source", exception)
            }
        }
        put(candidate, key, current)
        return stored
    }

    private fun validate(candidate: Node) {
        if (options.validators.isEmpty()) return
        val section = detachedSection(deepCopy(candidate))
        val violations = options.validators.flatMap { it.validate(section) }
        if (violations.isNotEmpty()) throw ConfigValidationException(violations)
    }

    private fun backup(target: Path, version: Int) {
        val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
        val backup = target.resolveSibling("${target.fileName}.v$version-$stamp.bak")
        try {
            Files.copy(target, backup, StandardCopyOption.REPLACE_EXISTING, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (exception: IOException) {
            throw ConfigException("Could not back up $target before migrating it", exception)
        }
    }

    private fun write(target: Path, text: String) {
        try {
            writeAtomically(target, text)
        } catch (exception: IOException) {
            throw ConfigException("Could not write $target", exception)
        }
    }

    override fun toString(): String = "${javaClass.simpleName}(${file ?: "detached"})"
}

/** A YAML document. Duplicate keys, aliases bombs and non-section roots are rejected. */
public class YamlConfig private constructor(file: Path?, options: ConfigOptions) :
    ConfigFile(file, ConfigFormat.YAML, options) {
    public companion object {
        /** Loads [path], creating it from the defaults in [options] when it does not exist. */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig =
            YamlConfig(path, options).also { it.reload() }

        @JvmStatic
        @JvmOverloads
        public fun load(file: File, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig = load(file.toPath(), options)

        /** Reads a detached document, for example a bundled resource. The stream is closed. */
        @JvmStatic
        @JvmOverloads
        public fun read(input: InputStream, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig =
            read(input.reader(), options)

        /** Reads a detached document. The reader is closed. */
        @JvmStatic
        @JvmOverloads
        public fun read(reader: Reader, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig =
            YamlConfig(null, options).also { it.initialize(reader.use(Reader::readText), "YAML input") }

        /** Parses a detached document from text. */
        @JvmStatic
        @JvmOverloads
        public fun parse(text: String, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig =
            YamlConfig(null, options).also { it.initialize(text, "YAML input") }
    }
}

/** A JSON document. Parsing is strict: comments, trailing commas and duplicate keys are rejected. */
public class JsonConfig private constructor(file: Path?, options: ConfigOptions) :
    ConfigFile(file, ConfigFormat.JSON, options) {
    public companion object {
        /** Loads [path], creating it from the defaults in [options] when it does not exist. */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig =
            JsonConfig(path, options).also { it.reload() }

        @JvmStatic
        @JvmOverloads
        public fun load(file: File, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig = load(file.toPath(), options)

        /** Reads a detached document. The stream is closed. */
        @JvmStatic
        @JvmOverloads
        public fun read(input: InputStream, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig =
            read(input.reader(), options)

        /** Reads a detached document. The reader is closed. */
        @JvmStatic
        @JvmOverloads
        public fun read(reader: Reader, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig =
            JsonConfig(null, options).also { it.initialize(reader.use(Reader::readText), "JSON input") }

        /** Parses a detached document from text. */
        @JvmStatic
        @JvmOverloads
        public fun parse(text: String, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig =
            JsonConfig(null, options).also { it.initialize(text, "JSON input") }
    }
}
