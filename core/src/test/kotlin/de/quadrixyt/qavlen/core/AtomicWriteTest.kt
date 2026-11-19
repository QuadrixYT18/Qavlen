package de.quadrixyt.qavlen.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Files
import java.nio.file.Path
import java.nio.file.attribute.PosixFilePermissions
import kotlin.io.path.createSymbolicLinkPointingTo
import kotlin.io.path.isSymbolicLink
import kotlin.io.path.readText
import kotlin.io.path.writeText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class AtomicWriteTest {
    @TempDir
    lateinit var directory: Path

    @Test
    fun `keeps the permissions of the replaced file`() {
        val target = directory.resolve("config.yml")
        target.writeText("old")
        Files.setPosixFilePermissions(target, PosixFilePermissions.fromString("rw-r--r--"))

        writeAtomically(target, "new")

        assertEquals("new", target.readText())
        assertEquals("rw-r--r--", PosixFilePermissions.toString(Files.getPosixFilePermissions(target)))
    }

    @Test
    fun `writes through symbolic links`() {
        val real = directory.resolve("shared/config.yml")
        Files.createDirectories(real.parent)
        real.writeText("old")
        val link = directory.resolve("config.yml").createSymbolicLinkPointingTo(real)

        writeAtomically(link, "new")

        assertTrue(link.isSymbolicLink())
        assertEquals("new", real.readText())
    }
}
