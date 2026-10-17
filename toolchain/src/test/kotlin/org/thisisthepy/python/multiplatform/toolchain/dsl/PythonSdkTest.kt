package org.thisisthepy.python.multiplatform.toolchain.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * `compileSdk` takes either form `(플러그인예시)build.gradle.kts` writes:
 *
 * - a string, `compileSdk = "3.11.9-alpha"`: any version. One the release server does not publish
 *   would be built automatically (INTENT §4.1), which is not available yet, so it is refused, inside
 *   the bundling task.
 * - a named constant, `compileSdk = PY3_15_0`: only versions the server publishes exist as constants.
 *
 * The runtime is CPython 3.15t, free-threaded only (python-multiplatform #158, toolchain #76): 3.14.7 and
 * 3.13.0 are gone, and 3.15.0 is the one version published.
 *
 * In a build script both are plain `=` (Gradle's Kotlin assignment overloading); plain Kotlin calls
 * `assign` directly.
 */
class PythonSdkTest {
    @Test
    fun `nothing assigned resolves to nothing`() {
        assertNull(resolvePythonSdk(PythonSdk()))
    }

    @Test
    fun `a named constant is a published version`() {
        val sdk = PythonSdk().apply { assign(PythonSdkVersion.PY3_15_0) }

        val resolved = resolvePythonSdk(sdk)!!

        assertEquals("3.15.0", resolved.version.toString())
        assertTrue(resolved.fromConstant)
        assertNull(resolved.rejection)
    }

    @Test
    fun `every constant names a version the release server publishes`() {
        PythonSdkVersion.values().forEach { constant ->
            assertTrue(constant.version in PythonReleaseServer.PUBLISHED, "$constant -> ${constant.version}")
        }
    }

    @Test
    fun `3_15_0 is the one published version and the one constant`() {
        assertEquals(listOf("3.15.0"), PythonReleaseServer.PUBLISHED)
        assertEquals(listOf(PythonSdkVersion.PY3_15_0), PythonSdkVersion.values().toList())
    }

    @Test
    fun `3_15_0 is a published constant and X dot Y resolves to it`() {
        val byConstant = resolvePythonSdk(PythonSdk().apply { assign(PythonSdkVersion.PY3_15_0) })!!
        assertEquals("3.15.0", byConstant.version.toString())
        assertNull(byConstant.rejection)
        assertEquals(true, byConstant.fromConstant)

        val byMinor = resolvePythonSdk(PythonSdk().apply { assign("3.15") })!!
        assertEquals("3.15.0", byMinor.version.toReleaseString())
        assertNull(byMinor.rejection)
    }

    @Test
    fun `a CPython before 3_15 is refused as a GIL build, inside the task`() {
        listOf("3.14.7", "3.14", "3.13.0", "3.13").forEach { raw ->
            val rejection = resolvePythonSdk(PythonSdk().apply { assign(raw) })!!.rejection.orEmpty()

            assertTrue(raw in rejection, rejection)
            assertTrue("free-threaded" in rejection && "3.15t" in rejection, rejection)
        }
    }

    @Test
    fun `the free-threaded spelling resolves to a published version and keeps the t`() {
        listOf("3.15t", "3.15.0t").forEach { raw ->
            val resolved = resolvePythonSdk(PythonSdk().apply { assign(raw) })!!

            assertNull(resolved.rejection, raw)
            assertEquals(true, resolved.version.freeThreaded, raw)
            assertEquals("3.15.0", resolved.version.toReleaseString(), raw)
            assertEquals("3.15.0t", resolved.version.toWheelVersion(), raw)
        }
        assertEquals("3.15.0", resolvePythonSdk(PythonSdk().apply { assign("3.15") })!!.version.toWheelVersion())
    }

    @Test
    fun `a string naming a published version is accepted, X dot Y included`() {
        listOf("3.15.0", "3.15").forEach { raw ->
            val resolved = resolvePythonSdk(PythonSdk().apply { assign(raw) })!!

            assertEquals("3.15.0", resolved.version.toReleaseString(), raw)
            assertNull(resolved.rejection, raw)
        }
    }

    @Test
    fun `a string the server does not publish is refused because auto-build is not available yet`() {
        val resolved = resolvePythonSdk(PythonSdk().apply { assign("3.15.9-alpha") })!!

        val rejection = resolved.rejection.orEmpty()
        assertTrue(rejection.contains("3.15.9-alpha"), rejection)
        assertTrue(rejection.contains("3.15.0"), "should list what is published: $rejection")
        assertTrue(rejection.contains("build", ignoreCase = true), rejection)
    }

    @Test
    fun `the last assignment wins, whichever form it uses`() {
        val sdk = PythonSdk().apply {
            assign(PythonSdkVersion.PY3_15_0)
            assign("3.11.9-alpha")
        }

        assertEquals(false, resolvePythonSdk(sdk)!!.fromConstant)
    }

    @Test
    fun `a malformed string still fails configuration`() {
        assertFailsWith<IllegalArgumentException> { resolvePythonSdk(PythonSdk().apply { assign("three") }) }
    }
}
