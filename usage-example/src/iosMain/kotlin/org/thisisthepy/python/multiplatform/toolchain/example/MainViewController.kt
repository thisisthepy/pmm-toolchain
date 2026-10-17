package org.thisisthepy.python.multiplatform.toolchain.example

import androidx.compose.ui.window.ComposeUIViewController

fun MainViewController() =
    // Headless check of the installed app's Python (toolchain#22); see PythonSmoke.ios.kt.
    run { runPythonSmokeIfRequested(); ComposeUIViewController { App() } }
