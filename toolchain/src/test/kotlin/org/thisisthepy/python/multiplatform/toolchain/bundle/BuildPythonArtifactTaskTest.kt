package org.thisisthepy.python.multiplatform.toolchain.bundle

import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Drives one DSL-to-`pypackpack` delegation all the way through: [bundleWithPackpack] (the
 * function [BuildPythonArtifactTask] calls from its `@TaskAction`) must actually invoke
 * `pypackpack`'s `resource` bundler (`BaseInterface.create(BundleType.RESOURCE)` ->
 * `ResourceBundler`) and produce its real on-disk output -- not just compile against the
 * dependency.
 *
 * Before this task's implementation exists, this test fails to compile (no `bundleWithPackpack`
 * function, no `packpack` dependency on the classpath) rather than failing an assertion -- that is
 * the pre-implementation failure this file is meant to record, per this repository's TDD rule.
 */
class BuildPythonArtifactTaskTest {
    private fun fixturePackageDir(): File {
        val root = kotlin.io.path.createTempDirectory("packpack-fixture").toFile()
        File(root, "pyproject.toml").writeText(
            """
            [project]
            name = "fixture-package"
            version = "0.0.1"
            """.trimIndent(),
        )
        val pkgDir = File(root, "src/main/fixture_package")
        pkgDir.mkdirs()
        File(pkgDir, "__init__.py").writeText("VALUE = 1\n")
        return root
    }

    @Test
    fun `bundleWithPackpack delegates to packpack's resource bundler and produces real output`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out").toFile()

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "macos",
            buildType = "debug",
            outputDir = outputDir,
        )

        assertEquals(1, result.fileCount)
        assertTrue(result.outputDir.resolve("python/fixture_package/__init__.py").isFile)
        assertTrue(result.manifestFile.isFile)
        assertTrue(
            result.manifestFile.readText().contains("\"packageName\": \"fixture-package\""),
            "manifest should be the real packpack ResourceBundler manifest, not a toolchain stand-in",
        )
    }

    /**
     * `bundleWithPackpack` reads `BuildPythonArtifactTask.minSdk` but, before this test, had no
     * parameter to carry it into the `BundleRequest` it builds -- see that task's kdoc on `minSdk`
     * ("It is logged, not forwarded"). `pypackpack`'s `6e36d3d` added `BundleRequest.minSdk` and
     * `ResourceBundler` records a declared value in its manifest
     * (`ResourceBundlerTest.bundle_manifestRecordsDeclaredMinSdkForAnAndroidTarget`). This test
     * proves the wiring all the way through: a `minSdk` passed to `bundleWithPackpack` for an
     * android-family target must show up in the real manifest `ResourceBundler` writes.
     *
     * Before `bundleWithPackpack` gains a `minSdk` parameter, this fails to compile -- the
     * pre-implementation failure this test is meant to record, not a regression.
     */
    @Test
    fun `bundleWithPackpack forwards minSdk to packpack's BundleRequest for an android target`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-minsdk").toFile()

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "aarch64-linux-android",
            buildType = "debug",
            outputDir = outputDir,
            minSdk = 24,
        )

        assertTrue(
            result.manifestFile.readText().contains("\"minSdk\": 24"),
            "declared minSdk should reach packpack's BundleRequest and be recorded in its manifest",
        )
    }

    /**
     * `defaultConfig { versionName; versionCode }` is the Python payload's version (#11), recorded
     * by pypackpack's `ResourceBundler` as `"versionName"` / `"versionCode"` beside the package's own
     * `"version"`.
     */
    @Test
    fun `bundleWithPackpack forwards the payload version to packpack's manifest`() {
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-version").toFile()

        val result = bundleWithPackpack(
            packageDir = fixturePackageDir(),
            target = "aarch64-apple-darwin",
            buildType = "debug",
            outputDir = outputDir,
            versionName = "1.0.0",
            versionCode = 1,
        )

        val manifest = result.manifestFile.readText()
        assertTrue(manifest.contains("\"versionName\": \"1.0.0\""), manifest)
        assertTrue(manifest.contains("\"versionCode\": 1"), manifest)
    }

    /**
     * `python { sourceSets { commonMain { metaDirs(...) } } }` had nowhere to go until `pypackpack`'s
     * `BundleRequest.metaDirs` existed for it to reach (see `PythonPluginSourceSetTest`'s
     * `resolveMetaDirs`, the pure function that reads the DSL). This proves the value does not just
     * compile through `bundleWithPackpack` -- the generated-meta file it names must land inside
     * `ResourceBundler`'s real on-disk payload.
     *
     * Before `bundleWithPackpack` gains a `metaDirs` parameter, this fails to compile -- the
     * pre-implementation failure this test is meant to record, not a regression.
     */
    @Test
    fun `bundleWithPackpack forwards metaDirs to packpack's BundleRequest and they land in the real payload`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-metadirs").toFile()
        val metaDir = kotlin.io.path.createTempDirectory("packpack-metadirs").toFile()
        File(metaDir, "fixture_package/api.pyi").apply { parentFile.mkdirs() }.writeText("def ping() -> str: ...\n")

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "macos",
            buildType = "debug",
            outputDir = outputDir,
            metaDirs = listOf(metaDir),
        )

        assertEquals(
            "def ping() -> str: ...\n",
            result.outputDir.resolve("python/fixture_package/api.pyi").readText(),
        )
        assertTrue(
            result.manifestFile.readText().contains("\"path\": \"python/fixture_package/api.pyi\""),
            "metaDirs file should be recorded in packpack's real manifest, not merely copied by toolchain",
        )
    }

    /** Same wiring as `metaDirs`, for `python { sourceSets { commonMain { libDirs(...) } } }`. */
    @Test
    fun `bundleWithPackpack forwards libDirs to packpack's BundleRequest and they land in the real payload`() {
        val packageDir = fixturePackageDir()
        val outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-libdirs").toFile()
        val libDir = kotlin.io.path.createTempDirectory("packpack-libdirs").toFile()
        File(libDir, "vendor_pkg/module.py").apply { parentFile.mkdirs() }.writeText("VENDORED = True\n")

        val result = bundleWithPackpack(
            packageDir = packageDir,
            target = "macos",
            buildType = "debug",
            outputDir = outputDir,
            libDirs = listOf(libDir),
        )

        assertEquals(
            "VENDORED = True\n",
            result.outputDir.resolve("python/vendor_pkg/module.py").readText(),
        )
    }

    /**
     * `compileSdk` must reach `BundleRequest.pythonVersion` so wheel tags follow the app's
     * interpreter (pypackpack#77: 3.15.0 is cp315-cp315, 3.15.0t is cp315-cp315t). Fails to compile
     * before `bundleRequestFor` and `wheelVersionOf` exist.
     */
    @Test
    fun `the compileSdk version is forwarded to packpack's BundleRequest pythonVersion`() {
        val request = bundleRequestFor(
            packageDir = fixturePackageDir(),
            target = "aarch64-apple-darwin",
            buildType = "debug",
            outputDir = kotlin.io.path.createTempDirectory("packpack-bundle-out-pyver").toFile(),
            pythonVersion = wheelVersionOf("3.15.0"),
        )
        assertEquals("3.15.0", request.pythonVersion)
        assertEquals("3.15.0t", wheelVersionOf("3.15.0t"))
        assertEquals("3.14.7", wheelVersionOf("3.14.7"))
        assertEquals(null, bundleRequestFor(fixturePackageDir(), "aarch64-apple-darwin", "debug", java.io.File(".")).pythonVersion)
    }
}
