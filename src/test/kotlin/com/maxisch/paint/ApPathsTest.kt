package com.maxisch.paint

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Preset names come straight from a text box and are turned into file names, so this is the one
 * place a typed name could escape `config/ap`.
 */
class ApPathsTest {

    @Test
    fun `plain names are left alone`() {
        assertEquals("default", ApPaths.sanitize("default"))
        assertEquals("my-preset_2.old", ApPaths.sanitize("my-preset_2.old"))
    }

    @Test
    fun `surrounding whitespace is trimmed`() {
        assertEquals("winter", ApPaths.sanitize("  winter  "))
    }

    @Test
    fun `path separators and traversal cannot survive`() {
        for (raw in listOf("../../etc/passwd", "..\\windows", "a/b", "a\\b", "C:name")) {
            val clean = ApPaths.sanitize(raw)
            assertFalse(clean.contains('/'), "'$clean' still has a forward slash")
            assertFalse(clean.contains('\\'), "'$clean' still has a backslash")
            assertFalse(clean.contains(':'), "'$clean' still has a colon")
        }
    }

    @Test
    fun `non-ascii is replaced rather than dropped`() {
        // Replacing keeps two different names different; dropping could collide them.
        assertEquals("caf_", ApPaths.sanitize("café"))
        assertNotEquals(ApPaths.sanitize("aé"), ApPaths.sanitize("a"))
    }

    @Test
    fun `names are capped at 64 characters`() {
        val long = "x".repeat(200)
        assertEquals(64, ApPaths.sanitize(long).length)
    }

    @Test
    fun `an empty or whitespace-only name comes back empty`() {
        // The callers rely on this: an empty result is what "blank name" means to them.
        assertTrue(ApPaths.sanitize("").isEmpty())
        assertTrue(ApPaths.sanitize("   ").isEmpty())
    }

    private fun assertNotEquals(a: String, b: String) =
        assertFalse(a == b, "expected '$a' and '$b' to differ")
}
