package com.folio.reader.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

actual fun decodeCoverImage(bytes: ByteArray): ImageBitmap? {
    return try {
        val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        buffered.toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

actual fun decodePageImage(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
    return try {
        val buffered = ImageIO.read(ByteArrayInputStream(bytes)) ?: return null
        if (targetWidthPx <= 0 || buffered.width <= targetWidthPx) {
            return buffered.toComposeImageBitmap()
        }
        val scale = targetWidthPx.toDouble() / buffered.width
        val newHeight = maxOf(1, (buffered.height * scale).toInt())
        val scaled = java.awt.image.BufferedImage(
            targetWidthPx, newHeight, java.awt.image.BufferedImage.TYPE_INT_ARGB
        )
        val g = scaled.createGraphics()
        try {
            g.setRenderingHint(
                java.awt.RenderingHints.KEY_INTERPOLATION,
                java.awt.RenderingHints.VALUE_INTERPOLATION_BILINEAR,
            )
            g.drawImage(buffered, 0, 0, targetWidthPx, newHeight, null)
        } finally {
            g.dispose()
        }
        scaled.toComposeImageBitmap()
    } catch (_: Exception) {
        decodeCoverImage(bytes)
    }
}

/**
 * Desktop: query skia's FontMgr for an installed system font (Windows fonts dir,
 * user fonts). "Georgia", "Times New Roman", "Segoe UI", user-installed Literata
 * etc. all resolve here with their REAL glyphs.
 */
private val fontFamilyCache = java.util.concurrent.ConcurrentHashMap<String, FontFamily?>()

actual fun systemFontFamily(name: String): FontFamily? {
    fontFamilyCache[name]?.let { return it }
    if (fontFamilyCache.containsKey(name)) return null
    // FontMgr loads the skiko native lib; in headless unit-test environments that
    // init throws Error subclasses — fall back to generic families there too.
    val result = try {
        val typeface = FontMgr.default.matchFamilyStyle(name, FontStyle.NORMAL)
        if (typeface == null) fallbackFontFamily(name) else FontFamily(Typeface(typeface))
    } catch (e: Throwable) {
        fallbackFontFamily(name)
    }
    fontFamilyCache[name] = result
    return result
}

/**
 * Fallback to generic font families when system font lookup fails
 */
private fun fallbackFontFamily(name: String): FontFamily? {
    val f = name.lowercase()
    return when {
        f.contains("mono") || f.contains("jetbrains") || f.contains("code") || 
            f.contains("courier") -> FontFamily.Monospace
        f.contains("serif") || f.contains("literata") || f.contains("merriweather") ||
            f.contains("garamond") || f.contains("lora") || f.contains("georgia") ||
            f.contains("times") || f.contains("playfair") || f.contains("crimson") ||
            f.contains("baskerville") || f.contains("pt serif") || 
            f.contains("source serif") || f.contains("calluna") -> FontFamily.Serif
        f.contains("sans") || f.contains("roboto") || f.contains("inter") || 
            f.contains("open") || f.contains("arial") || f.contains("helvetica") ||
            f.contains("segoe") || f.contains("comfortaa") || f.contains("noto sans") -> FontFamily.SansSerif
        else -> {
            println("Font system: No fallback match for '$name', using default")
            FontFamily.Default
        }
    }
}
