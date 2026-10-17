package org.thisisthepy.python.multiplatform.toolchain.example

import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import platform.Foundation.NSDocumentDirectory
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSSearchPathForDirectoriesInDomains
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.NSUserDomainMask
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.exit
import python.multiplatform.ffi.Python3

/**
 * `xcrun simctl launch <device> <bundle-id> --python-smoke` runs [runPythonSmokeIfRequested] and exits
 * before any UI, so CI can check the installed app's Python on a simulator (toolchain#22).
 *
 * It checks both halves of the .app: python-multiplatform's (the stdlib it copies into
 * `python-multiplatform-home/` and Python.framework) and toolchain's (`python/`: the app's `example_py`
 * and the dependency `iniconfig`).
 *
 * The report goes to stdout and to [PYTHON_SMOKE_FILE] in the app's Documents directory. CI reads the
 * file: `--console-pty` lost the output when the app had already exited by the time it attached, which is
 * how the job failed three times with nothing printed (toolchain#92).
 */
const val PYTHON_SMOKE_ARGUMENT = "--python-smoke"

/** The report's file name in the app's Documents directory (`simctl get_app_container <udid> <bundle> data`). */
const val PYTHON_SMOKE_FILE = "python-smoke.txt"

private const val PY_EVAL_INPUT = 258

fun runPythonSmokeIfRequested() {
    if (PYTHON_SMOKE_ARGUMENT !in NSProcessInfo.processInfo.arguments.map { it.toString() }) return
    val (code, report) =
        try {
            Python3.initialize()
            Python3.exec("import os, sys, example_py, iniconfig")
            val globals = Python3.import("__main__").dict
            val result =
                Python3.eval(
                    "f'{example_py.VERSION} {iniconfig.__name__} {sys.version.split()[0]}\\nPYTHON_SMOKE_STDLIB {os.path.realpath(os.__file__)}'",
                    PY_EVAL_INPUT,
                    globals,
                    globals,
                )
            0 to "PYTHON_SMOKE_OK $result"
        } catch (t: Throwable) {
            1 to "PYTHON_SMOKE_FAILED: $t"
        }
    println(report)
    writeSmokeReport("$report\n")
    exit(code)
}

@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
private fun writeSmokeReport(report: String) {
    val documents = NSSearchPathForDirectoriesInDomains(NSDocumentDirectory, NSUserDomainMask, true).firstOrNull() as? String ?: return
    NSString.create(string = report).writeToFile("$documents/$PYTHON_SMOKE_FILE", atomically = true, encoding = NSUTF8StringEncoding, error = null)
}
