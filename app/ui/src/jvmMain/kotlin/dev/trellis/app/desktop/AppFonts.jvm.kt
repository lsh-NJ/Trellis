package dev.trellis.app.desktop

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.platform.Font
import dev.trellis.ui.AppFontFamilies

/** 桌面：从 classpath 读打进 jar 的字体文件。 */
actual object AppFonts {

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

    actual val condensed: FontFamily = family(
        "barlow-condensed",
        load("/fonts/BarlowCondensed-Regular.ttf"),
        load("/fonts/BarlowCondensed-SemiBold.ttf"),
    )

    actual val mono: FontFamily = family(
        "ibm-plex-mono",
        load("/fonts/IBMPlexMono-Regular.ttf"),
        load("/fonts/IBMPlexMono-SemiBold.ttf"),
    )

    actual val families = AppFontFamilies(condensed = condensed, mono = mono, serif = FontFamily.Serif)
}
