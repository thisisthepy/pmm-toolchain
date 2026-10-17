package org.thisisthepy.python.multiplatform.toolchain

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertTrue

/**
 * `BuildType.compileLevel` -> `pypackpack`'s `BundleRequest.buildLevel` ([resolveBuildLevel]).
 *
 * `pypackpack`'s `ResourceBundler` (`bundle/resource/ResourceBundler.kt`, its `SUPPORTED_BUILD_LEVELS`)
 * implements `instant` and `bytecode` (Issue #15), so both pass through. `native` and `mixed` need
 * the compile stage's output, whose interface is pypackpack#19, so they are still refused -- per
 * variant, inside that variant's task action (AGENTS.md §14), with a message naming that slot.
 *
 * Blank resolves to `"instant"`: both `DebugBuildType` and `ReleaseBuildType` default `compileLevel`
 * to `""`, and `usage-example` never sets it.
 */
class PythonPluginBuildLevelTest {
    @Test
    fun `a blank compileLevel resolves to instant, matching the old hard-coded default`() {
        assertEquals("instant", resolveBuildLevel(""))
    }

    @Test
    fun `an explicit instant compileLevel passes through unchanged`() {
        assertEquals("instant", resolveBuildLevel("instant"))
    }

    @Test
    fun `bytecode passes through, because pypackpack's ResourceBundler implements it`() {
        assertEquals("bytecode", resolveBuildLevel("bytecode"))
    }

    @Test
    fun `native and mixed are rejected with a message naming the planned compile slot`() {
        listOf("native", "mixed").forEach { level ->
            val error = assertFailsWith<IllegalArgumentException> { resolveBuildLevel(level) }
            val message = error.message.orEmpty()
            assertTrue(message.contains("'$level'"), message)
            assertTrue(message.contains("pypackpack#19"), message)
            assertTrue(message.contains("'instant'") && message.contains("'bytecode'"), message)
        }
    }

    @Test
    fun `an unknown compileLevel is rejected too, not passed to pypackpack`() {
        val error = assertFailsWith<IllegalArgumentException> { resolveBuildLevel("bytcode") }
        assertTrue(error.message.orEmpty().contains("'bytcode'"), error.message)
    }
}
