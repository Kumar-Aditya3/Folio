package com.folio.reader.ui.theme

import android.os.Build
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Android: Compose loads a variable font file directly and pins the axis instances,
 * so a SemiBold heading is the designer's SemiBold and display sizes get Fraunces'
 * high-contrast optical cut — matching what skia renders on desktop. Below API 26
 * there is no variable-font API, so the system default beats a synthesised bold.
 */
actual fun fileFontFamily(
    path: String,
    weight: Int,
    italic: Boolean,
    opticalSize: Float
): FontFamily? {
    if (Build.VERSION.SDK_INT < 26) return null
    val file = File(path).takeIf { it.isFile } ?: return null
    return runCatching {
        FontFamily(
            Font(
                file = file,
                weight = FontWeight(weight),
                style = if (italic) FontStyle.Italic else FontStyle.Normal,
                variationSettings = if (opticalSize > 0f) {
                    FontVariation.Settings(*arrayOf(FontVariation.Setting("opsz", opticalSize)))
                } else {
                    FontVariation.Settings()
                }
            )
        )
    }.getOrNull()
}
