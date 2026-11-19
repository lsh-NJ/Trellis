package dev.trellis.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** 手机上窗口就是屏幕：取当前配置的屏宽（dp）。 */
@Composable
actual fun currentWindowWidth(): Dp = LocalConfiguration.current.screenWidthDp.dp
