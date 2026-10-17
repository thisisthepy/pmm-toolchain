package org.thisisthepy.python.multiplatform.toolchain.example

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.runtime.Composable
import androidx.compose.ui.tooling.preview.Preview


class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        // Headless check of the installed APK's Python (toolchain#22); see PythonSmoke.android.kt.
        if (intent?.getBooleanExtra(PYTHON_SMOKE_EXTRA, false) == true) {
            runPythonSmoke(this)
            finish()
            return
        }

        setContent {
            App()
        }
    }
}

@Preview
@Composable
fun AppAndroidPreview() {
    App()
}
