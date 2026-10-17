package org.thisisthepy.python.multiplatform.toolchain.hotreload

import org.gradle.api.DefaultTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputDirectory
import org.gradle.api.tasks.Optional
import org.gradle.api.GradleException
import org.gradle.api.tasks.TaskAction
import java.io.File

/**
 * `hotReloadPython`: registered by `PythonPlugin.apply` when a build type has `enableHotReload = true`.
 *
 * It does not work yet, and says so (toolchain#103): with hot reload enabled the task fails, naming why.
 * It used to `adb push` every `.py` and send [HOT_RELOAD_BROADCAST_ACTION], but nothing in the app receives
 * that broadcast, so the push looked like a working hot reload while the app never changed. The
 * live-connection channel replaces it (`docs/design/live-connection.md`, M2): one socket to the running app,
 * desktop, Android and iOS, carrying the changed files to the runtime, which re-imports them. Disabled, the
 * task prints how to enable it.
 */
abstract class HotReloadPushTask : DefaultTask() {

    /** Whether hotReload is enabled for the active build type. False => task prints a guide. */
    @get:Input
    var hotReloadEnabled: Boolean = false

    /** Local Python source root directory to push from. */
    @get:InputDirectory
    @get:Optional
    var sourceRoot: File? = null

    /**
     * On-device path where Python source files live, e.g. `/data/local/tmp/app/python`.
     * Set by `PythonPlugin.apply`; no default because the right path is app-specific.
     */
    @get:Input
    var remoteBasePath: String = ""

    /** Path to the `adb` executable. Defaults to `"adb"` (PATH lookup). */
    @get:Input
    var adbPath: String = "adb"

    /** Captured validated config for log output. */
    @get:Input
    var serverHost: String = ""

    @TaskAction
    fun push() {
        if (!hotReloadEnabled) {
            logger.lifecycle(
                "hotReloadPython: hot reload is not enabled for this build type. " +
                    "Set enableHotReload = true in python { buildTypes { getByName(\"debug\") { } } }.",
            )
            return
        }
        // toolchain#103: the adb push + RELOAD_PYTHON broadcast path had no receiver in the app, so it
        // looked like a working hot reload while nothing changed. It stays off until the live-connection
        // channel replaces it (docs/design/live-connection.md, M2); buildAdbPushCommands goes with it then.
        throw GradleException(
            "hotReloadPython: hot reload does not work yet. The app has no receiver for the RELOAD_PYTHON " +
                "broadcast this task used to send after `adb push`, so pushed files were never loaded (toolchain#103). " +
                "It is being replaced by a live connection to the running app (docs/design/live-connection.md); " +
                "until then, rebuild and reinstall the app to see changes.",
        )
    }
}
