package org.thisisthepy.python.multiplatform.toolchain

import org.thisisthepy.python.multiplatform.toolchain.dsl.HotReloadExtension
import org.thisisthepy.python.multiplatform.toolchain.dsl.PackagingExtension
import org.thisisthepy.python.multiplatform.toolchain.hotreload.HotReloadPushTask
import org.thisisthepy.python.multiplatform.toolchain.hotreload.validateHotReloadConfig
import org.gradle.api.GradleException
import org.gradle.testfixtures.ProjectBuilder
import kotlin.io.path.createTempDirectory
import kotlin.test.assertTrue
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith

/**
 * Tests for `python { packaging { hotReload { ... } } }` DSL validation.
 *
 * The DSL (`DSLPackaging.kt`) has carried `HotReloadExtension` fields since the first commit but
 * nothing in `PythonPlugin.apply` ever read them. A user declaring `hotReload { serverHost = "..." }`
 * got silence instead of an active task or an error.
 *
 * [validateHotReloadConfig] is factored out of `PythonPlugin.apply`'s `afterEvaluate` the same way
 * [resolveActiveBuildType] and [resolveBuildLevel] were -- a pure function testable without a Gradle
 * [org.gradle.api.Project].
 *
 * Boundary:
 *  - toolchain does: file-watch, adb push / local copy, trigger signal, DSL validation.
 *  - toolchain does NOT do: serve bundles (no server), reload running Python (python-multiplatform's
 *    runtime job), or act on serverHost until a server component exists.
 */
class PythonPluginHotReloadTest {

    private fun hotReloadTask(enabled: Boolean): HotReloadPushTask {
        val project = ProjectBuilder.builder().build()
        val sources = createTempDirectory("hot-reload-src").toFile().apply { resolve("app.py").writeText("X = 1\n") }
        return project.tasks.register("hotReloadPython", HotReloadPushTask::class.java) {
            hotReloadEnabled = enabled
            sourceRoot = sources
            remoteBasePath = "/data/local/tmp/app/python"
            // A path that cannot run: if the task tried `adb`, it would fail with an IOException instead.
            adbPath = "/nonexistent/adb-must-not-run"
        }.get()
    }

    /**
     * toolchain#103: nothing in python-multiplatform receives the RELOAD_PYTHON broadcast, so pushing files
     * would look like a working hot reload while the app never changes. Until the live-connection channel
     * replaces it (docs/design/live-connection.md, M2), an enabled task fails saying so, and runs no adb.
     */
    @Test
    fun `an enabled hot reload fails saying it does not work yet, without running adb`() {
        val task = hotReloadTask(enabled = true)

        val error = assertFailsWith<GradleException> { task.actions.forEach { it.execute(task) } }

        val message = error.message.orEmpty()
        assertTrue("does not work yet" in message && "RELOAD_PYTHON" in message && "#103" in message, message)
    }

    @Test
    fun `a disabled hot reload still only prints its guide`() {
        val task = hotReloadTask(enabled = false)
        task.actions.forEach { it.execute(task) }
    }

    @Test
    fun `disabled hotReload passes validation with empty serverHost`() {
        val ext = HotReloadExtension()
        val config = validateHotReloadConfig(enabled = false, ext)
        assertEquals(false, config.enabled)
    }

    @Test
    fun `enabled hotReload with blank serverHost fails with a clear message`() {
        val ext = HotReloadExtension().apply { serverHost = "" }
        val error = assertFailsWith<IllegalArgumentException> {
            validateHotReloadConfig(enabled = true, ext)
        }
        assertEquals(
            "python { buildTypes { ... } { enableHotReload = true } } requires " +
                "python { packaging { hotReload { serverHost = \"...\" } } } to be set.",
            error.message,
        )
    }

    @Test
    fun `enabled hotReload with valid serverHost passes`() {
        val ext = HotReloadExtension().apply { serverHost = "https://localhost:8080" }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals("https://localhost:8080", config.serverHost)
        assertEquals(true, config.enabled)
    }

    @Test
    fun `hotReload cert with both keyStore and autoGenerate fails`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert {
                keyStore = "path/to/keystore.jks"
                autoGenerate = true
            }
        }
        val error = assertFailsWith<IllegalArgumentException> {
            validateHotReloadConfig(enabled = true, ext)
        }
        assertEquals(
            "python { packaging { hotReload { cert { } } } }: " +
                "keyStore and autoGenerate are mutually exclusive; set only one.",
            error.message,
        )
    }

    @Test
    fun `hotReload cert with only autoGenerate passes`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert { autoGenerate = true }
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals(true, config.cert.autoGenerate)
        assertEquals(null, config.cert.keyStore)
    }

    @Test
    fun `hotReload cert with only keyStore passes`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            cert { keyStore = "path/to/keystore.jks" }
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals("path/to/keystore.jks", config.cert.keyStore)
        assertEquals(false, config.cert.autoGenerate)
    }

    @Test
    fun `redirectErrorStream is carried through to the config`() {
        val ext = HotReloadExtension().apply {
            serverHost = "https://localhost:8080"
            redirectErrorStream = true
        }
        val config = validateHotReloadConfig(enabled = true, ext)
        assertEquals(true, config.redirectErrorStream)
    }

    @Test
    fun `packaging with no hotReload block leaves enabled false`() {
        val packaging = PackagingExtension()
        val config = validateHotReloadConfig(enabled = false, packaging.hotReload)
        assertEquals(false, config.enabled)
    }
}
