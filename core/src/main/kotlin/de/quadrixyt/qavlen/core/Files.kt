@file:JvmName("QavlenFiles")

package de.quadrixyt.qavlen.core

import java.nio.charset.Charset
import java.nio.file.AtomicMoveNotSupportedException
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.StandardCopyOption

/**
 * Writes [text] to a temporary sibling file and moves it over [target], so readers never observe a partially
 * written file. Falls back to a non-atomic replace on file systems without atomic moves.
 */
@JvmOverloads
public fun writeAtomically(target: Path, text: String, charset: Charset = Charsets.UTF_8) {
    val absolute = target.toAbsolutePath()
    val directory = absolute.parent ?: throw IllegalArgumentException("$target has no parent directory")
    Files.createDirectories(directory)
    val temporary = Files.createTempFile(directory, ".${absolute.fileName}", ".tmp")
    try {
        Files.writeString(temporary, text, charset)
        try {
            Files.move(temporary, absolute, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
        } catch (_: AtomicMoveNotSupportedException) {
            Files.move(temporary, absolute, StandardCopyOption.REPLACE_EXISTING)
        }
    } finally {
        Files.deleteIfExists(temporary)
    }
}
