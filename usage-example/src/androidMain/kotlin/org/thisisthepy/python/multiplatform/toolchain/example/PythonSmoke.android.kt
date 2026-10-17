package org.thisisthepy.python.multiplatform.toolchain.example

import android.app.Activity
import android.util.Log
import python.multiplatform.env.PythonBootstrap
import python.multiplatform.ffi.Python3

/**
 * `adb shell am start -n <pkg>/.MainActivity --ez python-smoke true` runs [runPythonSmoke] and finishes
 * instead of showing the UI, so CI can check the installed APK's Python on an emulator (toolchain#22).
 *
 * It checks both halves of the APK: python-multiplatform's (the stdlib it ships and unpacks, started by
 * [PythonBootstrap.initialize]) and toolchain's (`assets/python/`: the app's `example_py` and the
 * dependency `iniconfig` installed for this ABI). Python's `print` goes nowhere on Android, so the result
 * is evaluated as a string and written to logcat under [PYTHON_SMOKE_TAG].
 */
const val PYTHON_SMOKE_EXTRA = "python-smoke"
const val PYTHON_SMOKE_TAG = "PythonSmoke"

private const val PY_EVAL_INPUT = 258

fun runPythonSmoke(activity: Activity): Boolean =
    try {
        PythonBootstrap.initialize(activity)
        Python3.exec("import os, sys, example_py, iniconfig")
        val globals = Python3.import("__main__").dict
        val report =
            Python3.eval(
                "f'{example_py.VERSION} {iniconfig.__name__} {sys.version.split()[0]} {os.path.realpath(os.__file__)}'",
                PY_EVAL_INPUT,
                globals,
                globals,
            )
        Log.i(PYTHON_SMOKE_TAG, "PYTHON_SMOKE_OK $report")
        true
    } catch (t: Throwable) {
        Log.e(PYTHON_SMOKE_TAG, "PYTHON_SMOKE_FAILED: $t", t)
        false
    }
