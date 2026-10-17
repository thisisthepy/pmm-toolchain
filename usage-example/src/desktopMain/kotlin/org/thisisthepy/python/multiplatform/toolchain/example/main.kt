package org.thisisthepy.python.multiplatform.toolchain.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application
import kotlin.system.exitProcess


fun main(args: Array<String>) {
    // Headless check of the packaged app's Python (toolchain#22); see PythonSmoke.kt.
    if (PYTHON_SMOKE_FLAG in args) exitProcess(runPythonSmoke())

    application {
        Window(
            onCloseRequest = ::exitApplication,
            title = "Toolchain",
        ) {
            App()
        }
    }
}
