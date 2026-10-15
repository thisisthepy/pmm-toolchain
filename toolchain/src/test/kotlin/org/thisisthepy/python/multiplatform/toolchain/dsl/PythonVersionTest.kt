package org.thisisthepy.python.multiplatform.toolchain.dsl

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * [PythonVersion.parse] and [PythonReleaseChannel] implement Issue #2's "Python version setup --
 * Version Enum (alpha, rc, normal)" checklist item.
 *
 * The format parsed is the one `(플러그인예시)build.gradle.kts` actually writes at its
 * `compileSdk = "3.11.9-alpha"` line: `X.Y`, `X.Y.Z`, `X.Y.Z-alpha[N]`, or `X.Y.Z-rc[N]`. This is
 * deliberately *not* the same grammar as `pypackpack`'s own `validatePythonVersion`
 * (`cli/.../CommandExtension.kt`), which only accepts `X.Y`/`X.Y.Z` with no channel suffix at all --
 * [PythonVersion.toReleaseString] is what bridges the two: it strips the channel back off before a
 * value reaches `ppp`.
 *
 * "do not support automatic build for new python release" (the issue's own qualifier on this item)
 * is why there is no network call, no "resolve the latest alpha" logic, and no attempt here to make
 * an alpha/rc version actually installable -- `pypackpack`'s backend
 * (`dependency/backend/DefaultInterface.kt`'s `installPython`) hard-rejects every version string
 * except the literal `"3.13"` today regardless of what this parser accepts. This type only
 * classifies a version string a consumer already wrote; it does not make more versions installable.
 */
class PythonVersionTest {
    @Test
    fun `X dot Y parses as a normal release with micro defaulted to zero`() {
        val version = PythonVersion.parse("3.13")

        assertEquals(3, version.major)
        assertEquals(13, version.minor)
        assertEquals(0, version.micro)
        assertEquals(PythonReleaseChannel.NORMAL, version.channel)
        assertEquals(null, version.serial)
    }

    @Test
    fun `X dot Y dot Z parses as a normal release`() {
        val version = PythonVersion.parse("3.13.1")

        assertEquals(3, version.major)
        assertEquals(13, version.minor)
        assertEquals(1, version.micro)
        assertEquals(PythonReleaseChannel.NORMAL, version.channel)
    }

    @Test
    fun `the alpha suffix from the reference DSL file parses as the ALPHA channel`() {
        val version = PythonVersion.parse("3.11.9-alpha")

        assertEquals(3, version.major)
        assertEquals(11, version.minor)
        assertEquals(9, version.micro)
        assertEquals(PythonReleaseChannel.ALPHA, version.channel)
    }

    @Test
    fun `an alpha suffix with a serial number captures the serial`() {
        val version = PythonVersion.parse("3.14.0-alpha2")

        assertEquals(PythonReleaseChannel.ALPHA, version.channel)
        assertEquals(2, version.serial)
    }

    @Test
    fun `an rc suffix parses as the RC channel`() {
        val version = PythonVersion.parse("3.14.0-rc1")

        assertEquals(PythonReleaseChannel.RC, version.channel)
        assertEquals(1, version.serial)
    }

    @Test
    fun `toReleaseString strips the channel suffix for ppp's own validator`() {
        assertEquals("3.11.9", PythonVersion.parse("3.11.9-alpha").toReleaseString())
        assertEquals("3.14.0", PythonVersion.parse("3.14.0-rc1").toReleaseString())
        assertEquals("3.13.0", PythonVersion.parse("3.13").toReleaseString())
    }

    @Test
    fun `garbage input is rejected loudly rather than silently defaulted`() {
        val error = assertFailsWith<IllegalArgumentException> { PythonVersion.parse("not-a-version") }
        assertEquals(
            "Invalid Python version 'not-a-version'. Expected 'X.Y', 'X.Y.Z', 'X.Y.Z-alpha[N]', " +
                "or 'X.Y.Z-rc[N]', each optionally followed by 't' (free-threaded).",
            error.message,
        )
    }

    @Test
    fun `an unknown channel word is rejected loudly`() {
        assertFailsWith<IllegalArgumentException> { PythonVersion.parse("3.13.0-beta1") }
    }

    @Test
    fun `a trailing t marks the free-threaded build and the wheel version keeps it`() {
        val threaded = PythonVersion.parse("3.15t")

        assertEquals(true, threaded.freeThreaded)
        assertEquals("3.15.0", threaded.toReleaseString())
        assertEquals("3.15.0t", threaded.toWheelVersion())
        assertEquals("3.15.0t", threaded.toString())
        assertEquals(false, PythonVersion.parse("3.15.0").freeThreaded)
        assertEquals("3.15.0", PythonVersion.parse("3.15.0-rc1").toWheelVersion())
    }
}
