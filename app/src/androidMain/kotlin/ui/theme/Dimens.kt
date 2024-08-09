package ui.theme

import androidx.compose.foundation.layout.BoxWithConstraintsScope
import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.TextUnit
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp


val defaultCornerRadius: Dp = 36.dp

val bodyTextFontSize: TextUnit = 16.sp
val headTextFontSize: TextUnit = 22.sp
val footTextFontSize: TextUnit = 12.sp
val statusTextFontSize: TextUnit = 26.sp

val horizontalLayoutThreshold = 600.dp
@Composable
fun BoxWithConstraintsScope.isHorizontal(): Boolean = this.maxWidth > horizontalLayoutThreshold
