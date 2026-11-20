package de.quadrixyt.qavlen.config

import de.quadrixyt.qavlen.core.backupFile
import de.quadrixyt.qavlen.core.createIfMissing
import de.quadrixyt.qavlen.core.readBundledResource
import de.quadrixyt.qavlen.core.writeAtomically
import java.io.File
import java.io.IOException
import java.io.InputStream
import java.io.Reader
import java.io.Writer
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.FileTime
import java.util.concurrent.CopyOnWriteArrayList
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
 * YAML comments written by people are kept when the file is rewritten (see [preserveYamlComments]); JSON has no
 * comments. Files loaded with a class loader start as a copy of the bundled resource of the same name, and later
 * versions of that resource only add missing keys (see [YamlConfig.load]).
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
    private var fileStamp: FileStamp? = null
    private val listeners = CopyOnWriteArrayList<ConfigListener>()

    /** Parsed messages for `getComponent`; every change of the values invalidates them. */
    internal val messages = MessageCache(this)
    private val view = NodeSection(lock, "", { path -> changed(path) }, messages) { root }

    /** A file version that failed to reload automatically, so it is reported once instead of on every lookup. */
    @Volatile
    private var failedStamp: FileStamp? = null

    /** The file's text as last read or written, to notice edits made by others before [save] overwrites them. */
    private var diskText: String? = null

    /** Text of the bundled resource this file was created from, whose comments new keys receive. */
    private var bundled: String? = null

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
    public fun reload(): List<String> = reload(created = false)

    /** [created] is `true` right after the file was copied from the bundled resource, so it counts as new. */
    private fun reload(created: Boolean): List<String> {
        val target = file ?: throw ConfigException("Cannot reload a configuration that was not loaded from a file")
        val (added, changed) = lock.write {
            val stamp = FileStamp.of(target)
            val text = try {
                if (Files.exists(target)) Files.readString(target) else null
            } catch (exception: IOException) {
                throw ConfigException("Could not read $target", exception)
            }
            val before = root
            val added = apply(text, target.toString(), created, stamp)
            fileStamp = if (Files.exists(target)) FileStamp.of(target) else stamp
            added to changedPaths(before, root)
        }
        if (changed.isNotEmpty()) notify(changed, reload = true)
        return added
    }

    /**
     * Reloads only when the file's modification time or size changed since it was last read or written, for
     * example from a repeating task. Returns `true` when a reload happened.
     *
     * @throws ConfigException like [reload]; the previous values stay active
     */
    public fun reloadIfModified(): Boolean {
        val target = file ?: throw ConfigException("Cannot reload a configuration that was not loaded from a file")
        if (lock.read { FileStamp.of(target) == fileStamp }) return false
        reload()
        return true
    }

    /** Registers [listener]; closing the returned handle removes it. */
    public fun addListener(listener: ConfigListener): AutoCloseable {
        listeners += listener
        return AutoCloseable { listeners -= listener }
    }

    private fun changed(path: String) {
        messages.invalidate()
        notify(setOf(path), reload = false)
    }

    /**
     * Reloads the file when it was edited on disk, for the message cache. Unsaved changes in memory win: the file is
     * then left alone, and [save] reports the conflict. A file that cannot be loaded keeps the current values and is
     * logged once per version.
     */
    internal fun reloadIfEditedOnDisk() {
        val target = file ?: return
        val stamp = FileStamp.of(target) ?: return
        if (lock.read { stamp == fileStamp } || stamp == failedStamp || isDirty) return
        try {
            reload()
        } catch (exception: ConfigException) {
            failedStamp = stamp
            System.getLogger(ConfigFile::class.java.name)
                .log(System.Logger.Level.WARNING, "$target was edited but could not be reloaded; keeping the previous values", exception)
        }
    }

    private fun notify(paths: Set<String>, reload: Boolean) {
        if (listeners.isEmpty()) return
        val change = ConfigChange(this, paths, reload)
        for (listener in listeners) {
            try {
                listener.onChange(change)
            } catch (exception: RuntimeException) {
                System.getLogger(ConfigFile::class.java.name)
                    .log(System.Logger.Level.ERROR, "A configuration listener failed for $this", exception)
            }
        }
    }

    /**
     * Validates and writes the current values to the backing file.
     *
     * Edits someone else made to the file since it was loaded, reloaded or saved are not overwritten: [save] throws
     * [ConfigConflictException] instead. Call [reload] to take them over, or pass `true` to replace them.
     *
     * @param overwriteExternalChanges write even when the file was changed on disk in the meantime
     * @return `false` when the file already contained the same content and nothing was written
     * @throws ConfigConflictException when the file changed on disk and [overwriteExternalChanges] is `false`
     */
    @JvmOverloads
    public fun save(overwriteExternalChanges: Boolean = false): Boolean {
        val target = file ?: throw ConfigException("This configuration has no backing file; use saveTo(path)")
        return lock.write {
            validate(root)
            val current = readOrNull(target)
            if (!overwriteExternalChanges && current != null && current != diskText) {
                throw ConfigConflictException(
                    "$target was changed on disk since it was loaded; call reload() to take over those changes " +
                        "or save(true) to replace them",
                )
            }
            val text = format.encode(root)
            if (text == persisted && current != null) return@write false
            write(target, withComments(current, text))
            persisted = text
            fileStamp = FileStamp.of(target)
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

    /**
     * Copies [resource] to the file when it does not exist yet, keeping its comments and formatting, then loads it.
     * A file that exists keeps every value; keys only the resource defines are added through the defaults.
     */
    internal fun loadBundled(resource: String) {
        bundled = resource
        val target = checkNotNull(file)
        val created = try {
            createIfMissing(target, resource)
        } catch (exception: IOException) {
            throw ConfigException("Could not create $target from its bundled resource", exception)
        }
        reload(created)
    }

    private fun apply(text: String?, source: String, created: Boolean = false, stamp: FileStamp? = null): List<String> {
        val candidate = text?.let { format.decode(it, source) } ?: linkedMapOf()
        val loadedText = text?.let { format.encode(candidate) }
        val migratedFrom = migrate(candidate, isNew = text == null || created, source)
        // Without writeDefaults, a migration is still saved, but the defaults added below stay in memory only.
        val migrated = if (options.writeDefaults || migratedFrom == null) null else format.encode(candidate)
        val added = mergeDefaults(candidate, options.defaults)
        validate(candidate)

        val target = file
        val content = migrated ?: format.encode(candidate)
        val write = target != null && content != loadedText && (options.writeDefaults || migratedFrom != null)
        if (write) {
            // Someone edited the file while it was being loaded; their edit wins and nothing is written.
            if (stamp != null && FileStamp.of(target) != stamp) {
                throw ConfigException("$target was changed while it was loaded; nothing was written")
            }
            if (migratedFrom != null && options.backups && text != null) backup(target, migratedFrom)
            write(target, withComments(text, content))
        }
        messages.invalidate()
        persisted = when {
            write -> content
            target == null -> format.encode(candidate)
            else -> loadedText
        }
        if (!write) diskText = text
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

    /** Copies the file before a migration rewrites it. Existing backups are never replaced. */
    private fun backup(target: Path, version: Int) {
        try {
            backupFile(target, "v$version")
        } catch (exception: IOException) {
            throw ConfigException("Could not back up $target before migrating it", exception)
        }
    }

    /**
     * Carries comments a person wrote in [previous] over to [content], and comments of the bundled resource to keys
     * that are new; only YAML has comments.
     */
    private fun withComments(previous: String?, content: String): String {
        if (format != ConfigFormat.YAML || previous == null) return content
        return bundled?.let { preserveYamlComments(previous, content, it) } ?: preserveYamlComments(previous, content)
    }

    private fun readOrNull(target: Path): String? = try {
        if (Files.exists(target)) Files.readString(target) else null
    } catch (_: IOException) {
        null
    }

    private fun write(target: Path, text: String) {
        try {
            writeAtomically(target, text)
            diskText = text
        } catch (exception: IOException) {
            throw ConfigException("Could not write $target", exception)
        }
    }

    /** Modification time and size, to notice edits without reading the file. */
    private data class FileStamp(val modified: FileTime, val size: Long) {
        companion object {
            fun of(path: Path): FileStamp? = try {
                FileStamp(Files.getLastModifiedTime(path), Files.size(path))
            } catch (_: IOException) {
                null
            }
        }
    }

    override fun toString(): String = "${javaClass.simpleName}(${file ?: "detached"})"
}

/**
 * Reads the resource named like [path]'s file, for example `messages.yml`, from the root of [loader].
 *
 * @throws ConfigException when the resource does not exist or cannot be read
 */
internal fun bundledResource(path: Path, loader: ClassLoader): String {
    val text = try {
        readBundledResource(path, loader)
    } catch (exception: IOException) {
        throw ConfigException("Could not read the bundled resource '${path.fileName}'", exception)
    }
    return text ?: throw ConfigException(
        "No bundled resource '${path.fileName}' for $path; add src/main/resources/${path.fileName} or load the file without a class loader",
    )
}

/** The class loader of [owner]; classes of the JDK itself have none, so the system class loader stands in. */
internal fun resourceLoader(owner: Class<*>): ClassLoader = owner.classLoader ?: ClassLoader.getSystemClassLoader()

/** [this] with the values of [resource] as additional defaults; a broken resource fails here, before any file changes. */
internal fun ConfigOptions.withBundled(format: ConfigFormat, resource: String, path: Path): ConfigOptions =
    toBuilder().defaults(format.decode(resource, "bundled resource ${path.fileName}")).build()

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

        /**
         * Loads [path] with the resource of the same file name in [classLoader] as its default, for example
         * `messages.yml` from `src/main/resources`. When the file does not exist, the resource is copied unchanged,
         * comments and formatting included. When it exists, only keys missing from the file are added, with the
         * comments the resource has for them; values in the file are never replaced and nothing is written when
         * nothing is missing. [options] can add validators, migrations and more defaults.
         *
         * @throws ConfigException when the resource does not exist or cannot be parsed, or the file cannot be read
         */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, classLoader: ClassLoader, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig {
            val resource = bundledResource(path, classLoader)
            return YamlConfig(path, options.withBundled(ConfigFormat.YAML, resource, path)).also { it.loadBundled(resource) }
        }

        /** Like [load] with a class loader, using the one that loaded [resourceOwner], typically the plugin's main class. */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, resourceOwner: Class<*>, options: ConfigOptions = ConfigOptions.DEFAULT): YamlConfig =
            load(path, resourceLoader(resourceOwner), options)

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

        /**
         * Loads [path] with the resource of the same file name in [classLoader] as its default, for example
         * `messages.json` from `src/main/resources`. When the file does not exist, the resource is copied unchanged,
         * comments and formatting included. When it exists, only keys missing from the file are added, with the
         * comments the resource has for them; values in the file are never replaced and nothing is written when
         * nothing is missing. [options] can add validators, migrations and more defaults.
         *
         * @throws ConfigException when the resource does not exist or cannot be parsed, or the file cannot be read
         */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, classLoader: ClassLoader, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig {
            val resource = bundledResource(path, classLoader)
            return JsonConfig(path, options.withBundled(ConfigFormat.JSON, resource, path)).also { it.loadBundled(resource) }
        }

        /** Like [load] with a class loader, using the one that loaded [resourceOwner], typically the plugin's main class. */
        @JvmStatic
        @JvmOverloads
        public fun load(path: Path, resourceOwner: Class<*>, options: ConfigOptions = ConfigOptions.DEFAULT): JsonConfig =
            load(path, resourceLoader(resourceOwner), options)

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
