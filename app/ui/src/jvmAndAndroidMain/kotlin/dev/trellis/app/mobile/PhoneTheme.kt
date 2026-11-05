package dev.trellis.app.mobile

import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Typography
import androidx.compose.material3.darkColorScheme
import androidx.compose.material3.lightColorScheme
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.trellis.app.desktop.AppFonts

/**
 * 手机端的设计底座。
 *
 * 和桌面共用一份**性格**（纸底墨色、方角细边、等宽小标），但量级全部按手指重排：
 * 卡片圆角 12dp（桌面 6dp，手机上小圆角显得刻薄）、控件 48dp（桌面 44dp）、
 * 页面左右 16dp、块间距 12dp。
 *
 * 为什么不直接沿用桌面主题：桌面的排版是为「一屏放很多信息」优化的 ——
 * 12 栏、1120dp 限宽、44dp 控件。把这些等比缩到 390dp，只会得到一屏密密麻麻的小字，
 * 那正是上一版难看的原因。
 */

private val Paper = Color(0xFFF6F3EB)
private val Card = Color(0xFFFDFBF5)
private val Ink = Color(0xFF22221E)
private val InkSoft = Color(0xFF6C6659)
private val Rule = Color(0xFFDDD6C4)
private val Pine = Color(0xFF3E6B56)
private val Clay = Color(0xFFA5553A)

private val Night = Color(0xFF141412)
private val NightCard = Color(0xFF1D1D19)
private val NightInk = Color(0xFFEDE7DA)
private val NightInkSoft = Color(0xFFA39C8C)
private val NightRule = Color(0xFF32312A)
private val PineLight = Color(0xFF8FBFA4)
private val ClayLight = Color(0xFFE08B6A)

/**
 * 主题包 / 用户配色会覆盖这些语义色，所以页面里**只读 MaterialTheme.colorScheme**，
 * 不要写死颜色常量。
 */
private val LightScheme = lightColorScheme(
    primary = Pine,
    onPrimary = Color.White,
    primaryContainer = Color(0xFFDCE8DF),
    onPrimaryContainer = Color(0xFF17301F),
    secondary = Clay,
    onSecondary = Color.White,
    background = Paper,
    onBackground = Ink,
    surface = Paper,
    onSurface = Ink,
    surfaceVariant = Card,
    onSurfaceVariant = InkSoft,
    outline = Rule,
    outlineVariant = Color(0xFFE9E3D5),
    error = Clay,
)

private val DarkScheme = darkColorScheme(
    primary = PineLight,
    onPrimary = Color(0xFF10231A),
    primaryContainer = Color(0xFF2A4436),
    onPrimaryContainer = Color(0xFFD7E9DC),
    secondary = ClayLight,
    onSecondary = Color(0xFF2A120A),
    background = Night,
    onBackground = NightInk,
    surface = Night,
    onSurface = NightInk,
    surfaceVariant = NightCard,
    onSurfaceVariant = NightInkSoft,
    outline = NightRule,
    outlineVariant = Color(0xFF27261F),
    error = ClayLight,
)

/** 卡片底色（比页面底略亮一档，浅色下几乎看不出差别，暗色下才有层次）。 */
val LocalCardColor = staticCompositionLocalOf { Card }

/** 等宽字体：数字、状态、栏目标签。 */
val PhoneMono: FontFamily get() = AppFonts.mono

/** 窄体：大标题、大数字。桌面的「编辑感」一半来自它。 */
val PhoneCondensed: FontFamily get() = AppFonts.condensed

private val base = Typography()

private val PhoneTypography = Typography(
    displaySmall = TextStyle(
        fontFamily = PhoneMono,
        fontWeight = FontWeight.Medium,
        fontSize = 32.sp,
        lineHeight = 36.sp,
        letterSpacing = (-0.8).sp,
    ),
    headlineMedium = base.headlineMedium.copy(fontWeight = FontWeight.SemiBold, fontSize = 24.sp, lineHeight = 32.sp),
    headlineSmall = base.headlineSmall.copy(fontWeight = FontWeight.SemiBold, fontSize = 19.sp, lineHeight = 26.sp),
    titleLarge = base.titleLarge.copy(fontWeight = FontWeight.SemiBold, fontSize = 18.sp, lineHeight = 24.sp),
    titleMedium = base.titleMedium.copy(fontWeight = FontWeight.Medium, fontSize = 16.sp, lineHeight = 22.sp),
    bodyLarge = base.bodyLarge.copy(fontSize = 16.sp, lineHeight = 25.sp),
    bodyMedium = base.bodyMedium.copy(fontSize = 14.sp, lineHeight = 22.sp),
    bodySmall = base.bodySmall.copy(fontSize = 13.sp, lineHeight = 19.sp),
    labelLarge = TextStyle(fontFamily = PhoneMono, fontSize = 13.sp, lineHeight = 17.sp, letterSpacing = 0.2.sp),
    labelMedium = TextStyle(fontFamily = PhoneMono, fontSize = 12.sp, lineHeight = 16.sp, letterSpacing = 0.3.sp),
    labelSmall = TextStyle(fontFamily = PhoneMono, fontSize = 11.sp, lineHeight = 14.sp, letterSpacing = 0.4.sp),
)

@Composable
fun PhoneTheme(appearance: String, content: @Composable () -> Unit) {
    val dark = when (appearance) {
        "light" -> false
        "dark" -> true
        else -> isSystemInDarkTheme()
    }
    val scheme = if (dark) DarkScheme else LightScheme
    CompositionLocalProvider(LocalCardColor provides if (dark) NightCard else Card) {
        MaterialTheme(
            colorScheme = scheme,
            typography = PhoneTypography,
            shapes = MaterialTheme.shapes.copy(
                extraSmall = RoundedCornerShape(6.dp),
                small = RoundedCornerShape(10.dp),
                medium = RoundedCornerShape(12.dp),
                large = RoundedCornerShape(18.dp),
            ),
            content = content,
        )
    }
}

/** 间距刻度：只用这几档，散着写必然不一致。 */
object Gap {
    val xs = 4.dp
    val s = 8.dp
    val m = 12.dp
    val l = 16.dp
    val xl = 20.dp
    val xxl = 28.dp
}

/** 拇指能稳定点到的下限。 */
val Touch = 48.dp
