@file:JvmName("QavlenFiles")

package de.quadrixyt.qavlen.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.FileAlreadyExistsException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption
import java.time.LocalDateTime
import java.time.format.DateTimeFormatter

/**
 * Writes [text] to a temporary sibling file, flushes it to disk and moves it over [target], so readers never observe
 * a partially written file and a crash leaves either the old or the new content. Falls back to a non-atomic replace
 * on file systems without atomic moves.
 *
 * POSIX permissions of an existing [target] are kept, and a symbolic link is written through instead of being
 * replaced by a regular file.
 */
@JvmOverloads
public fun writeAtomically(target: Path, text: String, charset: Charset = Charsets.UTF_8) {
    val destination = resolveLinks(target.toAbsolutePath())
    val directory = destination.parent ?: throw IllegalArgumentException("$target has no parent directory")
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".${destination.fileName}", ".tmp")
    try {
        FileChannel.open(temporary, StandardOpenOption.WRITE, StandardOpenOption.TRUNCATE_EXISTING).use { channel ->
            val buffer = ByteBuffer.wrap(text.toByteArray(charset))
            while (buffer.hasRemaining()) channel.write(buffer)
            channel.force(true)
        }
        copyPermissions(destination, temporary)
        try {
            Files.move(temporary, destination, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, destination, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/**
 * Copies [file] to a sibling named `<name>.<label>-<timestamp>.bak` and returns the copy. An existing backup is never
 * replaced; a counter is appended when several backups are made within the same second.
 */
public fun backupFile(file: Path, label: String): Path {
    val stamp = LocalDateTime.now().format(DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss"))
    for (attempt in 1..MAX_BACKUPS_PER_SECOND) {
        val suffix = if (attempt == 1) "" else "-$attempt"
        val backup = file.resolveSibling("${file.fileName}.$label-$stamp$suffix.bak")
        try {
            return Files.copy(file, backup, StandardCopyOption.COPY_ATTRIBUTES)
        } catch (_: FileAlreadyExistsException) {
            continue
        }
    }
    throw IOException("Too many backups of $file within one second")
}

/** Follows symbolic links, also to targets that do not exist yet. */
private fun resolveLinks(path: Path): Path {
    var current = path
    repeat(MAX_LINKS) {
        if (!Files.isSymbolicLink(current)) return current
        current = current.resolveSibling(Files.readSymbolicLink(current)).normalize()
    }
    throw IOException("Too many levels of symbolic links: $path")
}

private fun copyPermissions(source: Path, target: Path) {
    if (!Files.exists(source)) return
    try {
        Files.setPosixFilePermissions(target, Files.getPosixFilePermissions(source))
    } catch (_: UnsupportedOperationException) {
        // Not a POSIX file system; the platform's defaults apply.
    }
}

private const val MAX_LINKS = 40
private const val MAX_BACKUPS_PER_SECOND = 1000

/**
 * Creates [target] with exactly [text] when it does not exist yet, for example from a bundled default file. The text
 * is written to a temporary sibling first and then moved without replacing, so a file someone created in the
 * meantime is kept and readers never see a partial file. Missing parent directories are created.
 *
 * @return `true` when the file was created, `false` when it already existed
 */
@JvmOverloads
public fun createIfMissing(target: Path, text: String, charset: Charset = Charsets.UTF_8): Boolean {
    if (Files.exists(target)) return false
    val directory = target.toAbsolutePath().parent ?: throw IllegalArgumentException("$target has no parent directory")
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".${target.fileName}", ".tmp")
    try {
        Files.writeString(temporary, text, charset)
        Files.move(temporary, target)
        return true
    } catch (_: FileAlreadyExistsException) {
        return false
    } finally {
        Files.deleteIfExists(temporary)
    }
}

/**
 * Reads the resource named like [file]'s file name, for example `messages.yml`, from the root of [loader], or returns
 * `null` when there is none. The stream is always closed.
 */
@JvmOverloads
public fun readBundledResource(file: Path, loader: ClassLoader, charset: Charset = Charsets.UTF_8): String? {
    val name = file.fileName?.toString() ?: throw IllegalArgumentException("$file has no file name")
    return loader.getResourceAsStream(name)?.use { it.readBytes().toString(charset) }
}
