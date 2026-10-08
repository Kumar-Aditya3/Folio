package com.folio.reader.ui.theme

import android.os.Build
import androidx.compose.ui.text.font.Font
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontVariation
import androidx.compose.ui.text.font.FontWeight
import java.io.File

/**
 * Android: Compose hands a variable font file straight to `Typeface.Builder`, so
 * every axis that matters has to be named in the variation settings. Declaring
 * `Font(weight = ...)` only tells Compose's matcher which face to pick — it never
 * moves the `wght` axis, and because that declared weight equals the weight each
 * `TextStyle` requests, Compose's synthesis step early-returns instead of faking
 * a bold. Pinning only `opsz` therefore left every style rendering at the file's
 * own `fvar` default: Fraunces at Black and Manrope at ExtraLight, collapsing the
 * display/body/label ladder to two weights. Naming `wght` too makes a SemiBold
 * heading the designer's SemiBold, matching what skia renders on desktop. Below
 * API 26 there is no variable-font API, so the system default beats a
 * synthesised bold.
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
        val axes = mutableListOf<FontVariation.Setting>(
            FontVariation.Setting("wght", weight.toFloat())
        )
        if (opticalSize > 0f) axes += FontVariation.Setting("opsz", opticalSize)
        FontFamily(
            Font(
                file = file,
                weight = FontWeight(weight),
                style = if (italic) FontStyle.Italic else FontStyle.Normal,
                variationSettings = FontVariation.Settings(*axes.toTypedArray())
            )
        )
    }.getOrNull()
}
