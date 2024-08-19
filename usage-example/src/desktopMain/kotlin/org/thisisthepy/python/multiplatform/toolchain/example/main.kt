package org.thisisthepy.python.multiplatform.toolchain.example

import androidx.compose.ui.window.Window
import androidx.compose.ui.window.application


fun main() = application {
    Window(
        onCloseRequest = ::exitApplication,
        title = "Toolchain",
    ) {
        App()
    }
}
