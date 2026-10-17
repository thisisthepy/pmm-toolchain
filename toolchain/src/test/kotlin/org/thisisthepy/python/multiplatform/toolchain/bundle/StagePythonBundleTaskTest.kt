package org.thisisthepy.python.multiplatform.toolchain.bundle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * The step `f60bc3b` named as the next blocker: *"nothing stages the bundle into Android assets,
 * iOS resources or desktop resources."*
 *
 * ## What is being staged, and from where
 *
 * `pypackpack`'s `ResourceBundler` writes `<bundleDir>/python/` plus a sibling
 * `resource-manifest.json`, and its own KDoc states what the first of those is for:
 *
 * > **Payload root is `python/`.** The bundle directory holds a `python/` subdirectory intended to
 * > be placed on `sys.path` verbatim (staged into Android assets, an iOS resource directory, or a
 * > desktop resource folder).
 *
 * So the unit of staging is that directory's *contents*, and the manifest is build metadata that
 * must not travel with it -- staged into a directory that later goes on `sys.path`, a stray
 * `resource-manifest.json` is a file an application can import nothing from and a packager has to
 * ship anyway.
 *
 * ## Why a clean sync rather than a copy
 *
 * The destination is an *input* to somebody else's packaging step -- a JVM resource root, an AGP
 * asset source root. A plain copy never removes what it stopped copying, so a module deleted from
 * the Python package keeps shipping in every APK and jar built on that machine forever. This is not
 * hypothetical for this repository: `python-multiplatform`'s own `copyAndroidPythonAssets` carries a
 * `doFirst { delete(...) }` for exactly this reason, with a comment saying that is how `include/`
 * and `test/` would otherwise have survived being excluded.
 *
 * These tests exercise [stagePythonPayload], the pure function [StagePythonBundleTask] calls from
 * its `@TaskAction`, so the staging rule is pinned without a Gradle `Project` -- the same split
 * `bundleWithPackpack`/`BuildPythonArtifactTask` already uses.
 */
class StagePythonBundleTaskTest {

    /** A bundle directory shaped exactly like `ResourceBundler`'s output. */
    private fun bundleDir(vararg payload: Pair<String, String>): File {
        val root = kotlin.io.path.createTempDirectory("staging-bundle").toFile()
        payload.forEach { (relative, content) ->
            val file = File(root, "python/$relative")
            file.parentFile.mkdirs()
            file.writeText(content)
        }
        File(root, "resource-manifest.json").writeText("""{"formatVersion": 1}""")
        return root
    }

    private fun emptyDestination(): File = kotlin.io.path.createTempDirectory("staging-dest").toFile()

    @Test
    fun `stages the bundle's python root under the destination's payload path`() {
        val bundle = bundleDir("fixture_pkg/__init__.py" to "VALUE = 1\n")
        val destination = emptyDestination()

        val result = stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT)

        assertEquals(1, result.fileCount)
        val staged = File(destination, "python/fixture_pkg/__init__.py")
        assertTrue(staged.isFile, "expected the payload at $staged")
        assertEquals("VALUE = 1\n", staged.readText())
    }

    /**
     * toolchain#105: a hot-reload build's payload carries python-multiplatform's dev-agent marker, and only
     * that build's. The runtime starts no agent without it (SPEC L-12), so a release payload must never keep
     * one from an earlier debug staging; the clean sync removes it like any other stale file.
     */
    @Test
    fun `the dev-agent marker is staged only when asked for, and a later staging without it removes it`() {
        val bundle = bundleDir("fixture_pkg/__init__.py" to "VALUE = 1\n")
        val destination = emptyDestination()
        val marker = File(destination, "python/python_multiplatform_devagent.enable")

        val debug = stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT, devAgentMarker = true)
        assertTrue(marker.isFile, "expected the marker at $marker")
        assertEquals(1, debug.fileCount, "the marker is not a payload file")

        stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT, devAgentMarker = false)
        assertFalse(marker.exists(), "a release staging kept the debug marker")
    }

    @Test
    fun `keeps nested package structure, since that is what import resolution reads`() {
        val bundle = bundleDir(
            "fixture_pkg/__init__.py" to "",
            "fixture_pkg/sub/__init__.py" to "",
            "fixture_pkg/sub/deep.py" to "X = 2\n",
            "fixture_pkg/data/table.json" to "{}",
        )
        val destination = emptyDestination()

        val result = stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT)

        assertEquals(4, result.fileCount)
        assertTrue(File(destination, "python/fixture_pkg/sub/deep.py").isFile)
        // Not just `.py`: `ResourceBundler` carries data files next to the modules deliberately
        // ("All files are carried, not just `.py`"), so dropping them here would silently undo that.
        assertTrue(File(destination, "python/fixture_pkg/data/table.json").isFile)
    }

    @Test
    fun `does not stage the resource manifest, which is build metadata rather than payload`() {
        val bundle = bundleDir("fixture_pkg/__init__.py" to "")
        val destination = emptyDestination()

        stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT)

        assertFalse(
            File(destination, "python/resource-manifest.json").exists(),
            "the manifest must not land inside the directory that goes on sys.path",
        )
        assertFalse(File(destination, "resource-manifest.json").exists())
    }

    @Test
    fun `removes what a previous run staged and the bundle no longer has`() {
        val destination = emptyDestination()
        stagePythonPayload(bundleDir("fixture_pkg/gone.py" to "OLD = 1\n"), destination,
            PythonStagingLayout.PAYLOAD_ROOT)
        assertTrue(File(destination, "python/fixture_pkg/gone.py").isFile, "precondition")

        val result = stagePythonPayload(bundleDir("fixture_pkg/__init__.py" to ""), destination,
            PythonStagingLayout.PAYLOAD_ROOT)

        assertEquals(1, result.fileCount)
        assertFalse(
            File(destination, "python/fixture_pkg/gone.py").exists(),
            "a module deleted from the package would otherwise keep shipping in every artifact",
        )
    }

    @Test
    fun `the bundle payload is a declared input, or Gradle skips this task and the artifact goes stale`() {
        // A regression guard for a defect this task actually had. With the bundle declared nowhere,
        // Gradle had nothing to compare and reported `stagePythonBundleDesktop UP-TO-DATE` on every
        // run after the first, so the staged tree -- and the jar built from it -- kept a payload
        // from an earlier revision of the Python package. Found by building `:usage-example:
        // desktopJar`, deleting a module, and building again: the module was still in the archive.
        //
        // Asserted on the annotation rather than through a Gradle run because up-to-date checking
        // needs a real build, and the thing that was wrong is exactly this declaration.
        val getter = StagePythonBundleTask::class.java.getDeclaredMethod("getBundlePayload")
        assertTrue(
            getter.annotations.any { it.annotationClass == org.gradle.api.tasks.InputFiles::class },
            "bundlePayload must be @InputFiles; without a declared input Gradle skips the task and " +
                "every artifact keeps the payload it was first built with",
        )
        assertFalse(
            getter.annotations.any { it.annotationClass == org.gradle.api.tasks.SkipWhenEmpty::class },
            "an empty payload must still run the task -- clearing a destination that should now be " +
                "empty is the case this task exists for, and @SkipWhenEmpty is what made " +
                "packagePython silently produce nothing",
        )
    }

    @Test
    fun `an empty bundle stages nothing and still creates the payload directory`() {
        // `BuildPythonArtifactTask` `mkdirs()` the bundle directory and skips packpack entirely when
        // no `python.localLibraryPath` is configured, so a bundle with no `python/` root is the
        // normal state of every consumer that has not pointed the DSL at a ppp package yet. It has
        // to be silence, not a failure -- and the directory still has to exist, because it is
        // already registered as somebody's resource/asset root by then.
        val bundle = kotlin.io.path.createTempDirectory("staging-empty-bundle").toFile()
        val destination = emptyDestination()

        val result = stagePythonPayload(bundle, destination, PythonStagingLayout.PAYLOAD_ROOT)

        assertEquals(0, result.fileCount)
        assertTrue(File(destination, "python").isDirectory)
    }
}
