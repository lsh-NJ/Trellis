package dev.trellis.app.desktop

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * 一套 8×8 的像素图标（宿主自己的导航/按钮用）。
 *
 * 为什么自己画：样片的编辑感靠方角、粗像素和等宽小标，M3 的圆润图标放进去就破功；
 * 而且 emoji 在不同系统上字形完全不同（Windows 是彩色，macOS 是另一种），没法当品牌资产。
 *
 * 这里只用 [Canvas] 画实心色块，不依赖任何图片资源，所以三端/暗色/任意主题色都能跟随；
 * 同一份形状也导出成了 SVG（`docs/assets/icons/`）方便以后做宣传图或别的端复用。
 */
internal enum class ChromeIcon { Tasks, Plan, Chat, Plugins, Diagnostics, Settings, Logo, Check }

@Composable
internal fun ChromeIconGlyph(icon: ChromeIcon, tint: Color, modifier: Modifier = Modifier.size(16.dp)) {
    Canvas(modifier) {
        val unit = size.minDimension / 8f
        fun block(x: Float, y: Float, w: Float = 1f, h: Float = 1f) {
            drawRect(color = tint, topLeft = Offset(x * unit, y * unit), size = Size(w * unit, h * unit))
        }

        when (icon) {
            // 待办：左边三只小方框，右边三条横线。
            ChromeIcon.Tasks -> {
                for (row in 0..2) {
                    val y = 1f + row * 2.5f
                    block(1f, y, 2f, 1f)
                    block(1f, y + 1f, 1f, 1f)
                    block(2f, y + 1f, 1f, 1f)
                    block(4f, y + 0.5f, 3f, 1f)
                }
            }

            // 对话：方框 + 左下角小尾巴（不是圆气泡，贴合像素风）。
            ChromeIcon.Chat -> {
                block(1f, 1f, 6f, 1f)
                block(1f, 5f, 6f, 1f)
                block(1f, 2f, 1f, 3f)
                block(6f, 2f, 1f, 3f)
                block(2f, 6f, 2f, 1f)
                block(2f, 7f, 1f, 1f)
            }

            // 插件：2×2 四块拼图，留 1 格缝。
            ChromeIcon.Plugins -> {
                block(1f, 1f, 2.5f, 2.5f)
                block(4.5f, 1f, 2.5f, 2.5f)
                block(1f, 4.5f, 2.5f, 2.5f)
                block(4.5f, 4.5f, 2.5f, 2.5f)
            }

            // 诊断：外框 + 中间一条折线（心电图的意思）。
            ChromeIcon.Diagnostics -> {
                block(1f, 1f, 6f, 1f)
                block(1f, 6f, 6f, 1f)
                block(1f, 2f, 1f, 4f)
                block(6f, 2f, 1f, 4f)
                block(2f, 4f, 1f, 1f)
                block(3f, 3f, 1f, 1f)
                block(4f, 4f, 1f, 1f)
                block(5f, 3f, 1f, 1f)
            }

            // 计划：上面一条时间轴，下面三个格子（一周一格的意思）。
            ChromeIcon.Plan -> {
                block(1f, 1f, 6f, 1f)
                block(1f, 3f, 1.6f, 1.6f)
                block(3.2f, 3f, 1.6f, 1.6f)
                block(5.4f, 3f, 1.6f, 1.6f)
                block(1f, 5.4f, 1.6f, 1.6f)
                block(3.2f, 5.4f, 3.8f, 1.6f)
            }

            // 品牌：一个像素化的 T。
            ChromeIcon.Logo -> {
                block(1f, 1f, 6f, 1f)
                block(3f, 2f, 2f, 6f)
            }

            // 设置：三条滑块，每条一个旋钮（像素风的「设置」比齿轮更好认）。
            ChromeIcon.Settings -> {
                block(1f, 2f, 6f, 1f)
                block(4.5f, 1.5f, 1f, 2f)
                block(1f, 4f, 6f, 1f)
                block(2f, 3.5f, 1f, 2f)
                block(1f, 6f, 6f, 1f)
                block(5.5f, 5.5f, 1f, 2f)
            }

            // 勾：两段斜角像素，用于「已完成」。
            ChromeIcon.Check -> {
                block(1f, 4f, 1f, 1f)
                block(2f, 5f, 1f, 1f)
                block(3f, 4f, 1f, 1f)
                block(4f, 3f, 1f, 1f)
                block(5f, 2f, 1f, 1f)
                block(6f, 1f, 1f, 1f)
            }
        }
    }
}
