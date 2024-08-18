package ui

import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import thisisthepy.pycomposeui.PythonAppView
import thisisthepy.pycomposeui.PythonLauncher
import ui.theme.AppTheme


@Preview
@Composable
fun App() {
    AppTheme {
        PythonLauncher {
            PythonAppView(Modifier.fillMaxSize())
        }
    }
}
