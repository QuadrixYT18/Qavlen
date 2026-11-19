@file:JvmName("QavlenFiles")

package de.quadrixyt.qavlen.core

import java.io.IOException
import java.nio.ByteBuffer
import java.nio.channels.FileChannel
import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption
import java.nio.file.StandardOpenOption

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
