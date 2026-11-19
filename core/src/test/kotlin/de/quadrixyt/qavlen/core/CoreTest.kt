package de.quadrixyt.qavlen.core

import org.junit.jupiter.api.io.TempDir
import java.nio.file.Path
import java.time.Duration
import kotlin.io.path.listDirectoryEntries
import kotlin.io.path.readText
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

class CoreTest {
    @Test
    fun `parses compact durations`() {
        assertEquals(Duration.ofMinutes(90), Durations.parse("1h30m"))
        assertEquals(Duration.ofDays(9), Durations.parse("1w 2d"))
        assertEquals(Duration.ofMillis(1500), Durations.parse("1s500ms"))
        assertEquals(Duration.ofMinutes(5), Durations.parse("PT5M"))
        assertFailsWith<IllegalArgumentException> { Durations.parse("5x") }
        assertFailsWith<IllegalArgumentException> { Durations.parse("h5") }
        assertFailsWith<IllegalArgumentException> { Durations.parse("") }
    }

    @Test
    fun `formats durations in parseable form`() {
        val duration = Duration.ofDays(8).plusMinutes(3).plusMillis(20)
        assertEquals("1w1d3m20ms", Durations.format(duration))
        assertEquals(duration, Durations.parse(Durations.format(duration)))
    }

    @Test
    fun `resource scope closes in reverse order and collects failures`() {
        val closed = mutableListOf<String>()
        val scope = ResourceScope()
        scope.own(AutoCloseable { closed += "first" })
        scope.own(AutoCloseable { throw IllegalStateException("second") })
        scope.own(AutoCloseable { closed += "third" })

        val failure = assertFailsWith<IllegalStateException> { scope.close() }

        assertEquals("second", failure.message)
        assertEquals(listOf("third", "first"), closed)
        scope.own(AutoCloseable { closed += "late" })
        assertEquals("late", closed.last())
    }

    @Test
    fun `atomic writes leave no temporary files`(@TempDir directory: Path) {
        val target = directory.resolve("nested/file.txt")
        writeAtomically(target, "one")
        writeAtomically(target, "two")

        assertEquals("two", target.readText())
        assertEquals(listOf(target), target.parent.listDirectoryEntries())
    }
}
