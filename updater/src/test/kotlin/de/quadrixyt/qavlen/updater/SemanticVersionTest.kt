package de.quadrixyt.qavlen.updater

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertTrue

class SemanticVersionTest {
    private fun v(text: String) = requireNotNull(SemanticVersion.parse(text)) { text }

    @Test
    fun `numbers are compared numerically`() {
        assertTrue(v("1.10.0").compareTo(v("1.9.0")) > 0)
        assertTrue(v("2.0").compareTo(v("1.99.99")) > 0)
        assertEquals(0, v("v1.0.0").compareTo(v("1.0.0")))
        assertEquals(0, v("1.2").compareTo(v("1.2.0")))
        assertTrue(v("1.0.0.1").compareTo(v("1.0.0")) > 0)
        assertTrue(v("99999999999999999999.0").compareTo(v("1.0")) > 0)
    }

    @Test
    fun `pre-releases are ordered and older than the release`() {
        val ordered = listOf("1.2.0-SNAPSHOT", "1.2.0-alpha.1", "1.2.0-beta.1", "1.2.0-beta.2", "1.2.0-beta.10", "1.2.0-rc.1", "1.2.0", "1.2.1-SNAPSHOT")
        assertEquals(ordered, ordered.shuffled(java.util.Random(7)).sortedWith { a, b -> v(a).compareTo(v(b)) })
        assertTrue(v("1.2.0-beta1").compareTo(v("1.2.0-beta.1")) == 0)
        assertTrue(v("1.2.0b2").compareTo(v("1.2.0-b.1")) > 0)
    }

    @Test
    fun `labels and build metadata do not change the order`() {
        assertEquals(0, v("5.5.71-velocity").compareTo(v("5.5.71")))
        assertEquals(0, v("1.0.0+build.5").compareTo(v("1.0.0")))
        assertTrue(v("5.5.72-paper").compareTo(v("5.5.71-velocity")) > 0)
        assertFalse(v("2.0.0-paper").isPrerelease)
    }

    @Test
    fun `channels follow the markers`() {
        assertEquals(UpdateChannel.STABLE, v("1.0.0").channel)
        assertEquals(UpdateChannel.BETA, v("1.0.0-rc.1").channel)
        assertEquals(UpdateChannel.BETA, v("1.0.0-beta").channel)
        assertEquals(UpdateChannel.ALPHA, v("1.0.0-alpha").channel)
        assertEquals(UpdateChannel.ALPHA, v("1.0.0-SNAPSHOT").channel)
        assertTrue(UpdateChannel.BETA.includes(UpdateChannel.STABLE))
        assertFalse(UpdateChannel.STABLE.includes(UpdateChannel.BETA))
    }

    @Test
    fun `versions that do not start with a number are not comparable`() {
        assertNull(SemanticVersion.parse("latest"))
        assertNull(SemanticVersion.parse("a1b2c3d"))
        assertNull(SemanticVersion.compare("1.0.0", "release"))
        assertEquals(1, SemanticVersion.compare("1.1", "1.0"))
    }
}
