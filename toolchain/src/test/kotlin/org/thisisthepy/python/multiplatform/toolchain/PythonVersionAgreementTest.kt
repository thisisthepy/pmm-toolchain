package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.PythonSdk
import org.thisisthepy.python.multiplatform.toolchain.dsl.resolvePythonSdk
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * docs/SPEC.md §1.12, issue #42: python-multiplatform embeds the interpreter and stdlib, so
 * `compileSdk` (which selects the wheels in `python/`) must name the same X.Y.Z as its
 * `pythonVersion`. [checkPythonVersionAgreement] decides; [selectPythonMultiplatformVersion] decides
 * where that version comes from. No Gradle project.
 */
class PythonVersionAgreementTest {
    private fun sdk(requested: String) = resolvePythonSdk(PythonSdk().apply { assign(requested) })

    @Test
    fun `the same X Y Z agrees`() {
        assertNull(checkPythonVersionAgreement(sdk("3.15.0"), "3.15.0"))
        // `X.Y` resolves to the newest published micro, which is what is compared.
        assertNull(checkPythonVersionAgreement(sdk("3.15"), "3.15.0"))
        assertNull(checkPythonVersionAgreement(sdk("3.15.0"), " 3.15.0 "))
    }

    @Test
    fun `a different X Y Z is rejected, naming both and where the embedded one comes from`() {
        val rejection = assertNotNull(checkPythonVersionAgreement(sdk("3.15.0"), "3.13.0"))
        assertTrue("3.13.0" in rejection && "3.15.0" in rejection, rejection)
        assertTrue("python-multiplatform's pythonVersion" in rejection && "gradle.properties" in rejection, rejection)
        assertTrue("embedded" in rejection, rejection)

        // Same minor, different micro: still a different interpreter (cf. iOS 3.14.6 against 3.14.7).
        assertNotNull(checkPythonVersionAgreement(sdk("3.15.0"), "3.15.1"))
    }

    @Test
    fun `either side unknown means no check`() {
        assertNull(checkPythonVersionAgreement(null, "3.15.0"))
        assertNull(checkPythonVersionAgreement(sdk("3.15.0"), null))
        assertNull(checkPythonVersionAgreement(null, null))
    }

    @Test
    fun `a python-multiplatform version that is not a version is rejected, naming it`() {
        val rejection = assertNotNull(checkPythonVersionAgreement(sdk("3.15.0"), "latest"))
        assertTrue("'latest'" in rejection && "3.15.0" in rejection, rejection)
    }

    @Test
    fun `the property wins over the extension, blank counts as unset, nothing means no check`() {
        assertEquals("3.13.0", selectPythonMultiplatformVersion("3.13.0", "3.15.0"))
        assertEquals("3.15.0", selectPythonMultiplatformVersion(null, "3.15.0"))
        assertEquals("3.15.0", selectPythonMultiplatformVersion("  ", " 3.15.0 "))
        assertEquals("3.13.0", selectPythonMultiplatformVersion("3.13.0", null))
        assertNull(selectPythonMultiplatformVersion(null, null))
        assertNull(selectPythonMultiplatformVersion("", " "))
    }
}
