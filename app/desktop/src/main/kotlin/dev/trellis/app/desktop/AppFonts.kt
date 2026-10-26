package dev.trellis.app.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.trellis.ui.AppFontFamilies

/**
 * 打进安装包的字体（Barlow Condensed、IBM Plex Mono，都在 OFL 下，
 * 许可证在 `resources/fonts/OFL-*.txt`）。
 *
 * 为什么自己带：样片的「编辑感」一半来自窄体的大数字和等宽小标。系统字体在
 * Windows / macOS / Linux 上各不相同，样式会随机器漂；带两套文件，三处才一致。
 * 读不到时退回系统字体 —— 界面照常能用，只是不像样片。
 */
internal object AppFonts {
    private fun load(path: String): ByteArray? =
        runCatching { AppFonts::class.java.getResourceAsStream(path)?.readBytes() }.getOrNull()

    private fun family(prefix: String, regular: ByteArray?, semibold: ByteArray?): FontFamily {
        if (regular == null) return FontFamily.Default
        return if (semibold == null) {
            FontFamily(
                Font(identity = prefix + "-400", data = regular, weight = FontWeight.Normal, style = FontStyle.Normal),
            )
        } else {
            FontFamily(
                Font(identity = prefix + "-400", data = regular, weight = FontWeight.Normal, style = FontStyle.Normal),
                Font(identity = prefix + "-600", data = semibold, weight = FontWeight.SemiBold, style = FontStyle.Normal),
            )
        }
    }

    val condensed: FontFamily = family(
        "barlow-condensed",
        load("/fonts/BarlowCondensed-Regular.ttf"),
        load("/fonts/BarlowCondensed-SemiBold.ttf"),
    )

    val mono: FontFamily = family(
        "ibm-plex-mono",
        load("/fonts/IBMPlexMono-Regular.ttf"),
        load("/fonts/IBMPlexMono-SemiBold.ttf"),
    )

    val families = AppFontFamilies(condensed = condensed, mono = mono, serif = FontFamily.Serif)
}
