package dev.trellis.app.desktop

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * 当前「窗口」宽度。
 *
 * 桌面要的是窗口宽度（可以拖大拖小），安卓没有窗口这个概念，取屏幕宽度。
 * 共用界面里判断「窄窗 / 宽窗」只看这一个数，平台差异收在 expect/actual 这一对里 ——
 * 原先三处直接读 `LocalWindowInfo.current.containerSize`，那份 API 只有 Compose Desktop 有。
 */
@Composable
expect fun currentWindowWidth(): Dp
