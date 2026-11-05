package dev.trellis.app.desktop

import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import dev.trellis.app.shared.R
import dev.trellis.ui.AppFontFamilies

/**
 * 安卓：字体打进 `res/font`，用 `Font(resId)` 加载。
 *
 * 和桌面读的是**同一批 TTF 文件**（OFL 许可，见 `res/font` 旁边那份许可证），
 * 所以两端的「窄体大数字 + 等宽小标」是同一个字形，不是「桌面好看、手机凑合」。
 */
actual object AppFonts {

    actual val condensed: FontFamily = FontFamily(
        Font(R.font.barlow_condensed_regular, FontWeight.Normal),
        Font(R.font.barlow_condensed_semibold, FontWeight.SemiBold),
    )

    actual val mono: FontFamily = FontFamily(
        Font(R.font.ibm_plex_mono_regular, FontWeight.Normal),
        Font(R.font.ibm_plex_mono_semibold, FontWeight.SemiBold),
    )

    actual val families = AppFontFamilies(condensed = condensed, mono = mono, serif = FontFamily.Serif)
}
