package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonSdk
import org.thisisthepy.python.multiplatform.toolchain.dsl.resolvePythonSdk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.12, `embedLevel`: [resolveEmbedLevel] is the whole decision (AGENTS.md §14), so
 * the table per platform family, the `gradle.properties` override and the invalid values are all
 * tested here without a Gradle project.
 */
class EmbedLevelTest {
    @Test
    fun `desktop families honour every declared level without a warning`() {
        listOf("macos", "linux", "windows").forEach { family ->
            (0..2).forEach { level ->
                assertEquals(level to null, resolveEmbedLevel(level, null, family), "$family/$level")
            }
        }
    }

    @Test
    fun `android and ios raise 0 and 1 to 2 with a warning naming the platform`() {
        listOf("android", "ios").forEach { family ->
            listOf(0, 1).forEach { level ->
                val (resolved, warning) = resolveEmbedLevel(level, null, family)
                assertEquals(2, resolved, "$family/$level")
                val text = assertNotNull(warning, "$family/$level")
                assertTrue(text.contains(family) && text.contains("embedLevel = $level") && text.contains("2"), text)
            }
            assertEquals(2 to null, resolveEmbedLevel(2, null, family))
        }
    }

    @Test
    fun `the property overrides the declared level, and is then subject to the same raise`() {
        assertEquals(1 to null, resolveEmbedLevel(0, "1", "linux"))
        assertEquals(0 to null, resolveEmbedLevel(2, " 0 ", "macos"))
        val (level, warning) = resolveEmbedLevel(2, "1", "android")
        assertEquals(2, level)
        assertNotNull(warning)
    }

    @Test
    fun `a null or blank override leaves the declared level`() {
        assertEquals(1 to null, resolveEmbedLevel(1, null, "linux"))
        assertEquals(1 to null, resolveEmbedLevel(1, "  ", "linux"))
    }

    @Test
    fun `a value outside 0 to 2 or not a number fails, naming the value and the property`() {
        listOf("3", "-1", "two", "1.5").forEach { bad ->
            val error = assertFailsWith<IllegalArgumentException>(bad) { resolveEmbedLevel(0, bad, "linux") }
            val message = error.message.orEmpty()
            assertTrue(message.contains("python.embedLevel") && message.contains("'$bad'"), message)
        }
        listOf(3, -1).forEach { bad ->
            val error = assertFailsWith<IllegalArgumentException> { resolveEmbedLevel(bad, null, "linux") }
            assertTrue(error.message.orEmpty().contains("embedLevel") && error.message.orEmpty().contains("$bad"), error.message)
        }
    }

    @Test
    fun `an unknown family is treated as unable to use an external interpreter`() {
        assertNull(resolveEmbedLevel(2, null, "wasm").second)
        assertEquals(2, resolveEmbedLevel(0, null, "wasm").first)
    }

    @Test
    fun `the embed record is JSON carrying the level, family, warning and expected interpreter version`() {
        val json = embedRecordJson(2, "android", "raised \"x\"", "3.15.0")
        assertTrue(json.contains("\"embedLevel\": 2"), json)
        assertTrue(json.contains("\"platformFamily\": \"android\""), json)
        assertTrue(json.contains("raised \\\"x\\\""), json)
        assertTrue(json.contains("\"interpreterVersion\": \"3.15.0\""), json)
        // toolchain ships no interpreter (SPEC §1.12, #42), so the record no longer says whether one is bundled.
        assertFalse(json.contains("interpreterBundled"), json)
        val none = embedRecordJson(0, "linux", null)
        assertTrue(none.contains("\"warning\": null") && none.contains("\"interpreterVersion\": null"), none)
    }

    private fun sdk(requested: String) = resolvePythonSdk(PythonSdk().apply { assign(requested) })

    @Test
    fun `the expected interpreter version is the compileSdk release at levels 1 and 2, none at 0`() {
        assertNull(expectedInterpreterVersion(0, sdk("3.15.0")))
        assertEquals("3.15.0", expectedInterpreterVersion(1, sdk("3.15")))
        assertEquals("3.15.0", expectedInterpreterVersion(2, sdk("3.15.0")))
        assertNull(expectedInterpreterVersion(2, null))
        // A rejected compileSdk (not provided by python-multiplatform) names no version to expect.
        assertNull(expectedInterpreterVersion(2, sdk("3.11.9-alpha")))
    }
}
