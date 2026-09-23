package com.folio.reader.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontStyle
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO

/** Covers render in small cells; decoding them past ~1200px wastes memory (a cover cell is ≤300dp). */
private const val COVER_MAX_WIDTH_PX = 1200

/**
 * Reads [bytes] at reduced resolution using the JDK ImageReader's source subsampling, so a large
 * image is never fully materialised at native resolution before scaling. Falls back to a full
 * `ImageIO.read` when the format has no reader or subsampling is not needed. Width-based only:
 * an image already at/under [targetWidthPx] is read unscaled.
 */
private fun readSubsampled(bytes: ByteArray, targetWidthPx: Int): java.awt.image.BufferedImage? {
    if (targetWidthPx <= 0) return ImageIO.read(ByteArrayInputStream(bytes))
    ImageIO.createImageInputStream(ByteArrayInputStream(bytes)).use { iis ->
        if (iis == null) return null
        val readers = ImageIO.getImageReaders(iis)
        if (!readers.hasNext()) return ImageIO.read(ByteArrayInputStream(bytes))
        val reader = readers.next()
        return try {
            reader.setInput(iis, true, true)
            val w = reader.getWidth(0)
            val param = reader.defaultReadParam
            val factor = if (w > targetWidthPx) maxOf(1, w / targetWidthPx) else 1
            if (factor > 1) param.setSourceSubsampling(factor, factor, 0, 0)
            reader.read(0, param)
        } catch (_: Exception) {
            runCatching { ImageIO.read(ByteArrayInputStream(bytes)) }.getOrNull()
        } finally {
            reader.dispose()
        }
    }
}

actual fun decodeCoverImage(bytes: ByteArray): ImageBitmap? {
    return try {
        // Subsample to cover size instead of decoding the (often 2000px+) source at full resolution.
        (readSubsampled(bytes, COVER_MAX_WIDTH_PX) ?: return null).toComposeImageBitmap()
    } catch (_: Exception) {
        null
    }
}

actual fun decodePageImage(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
    return try {
        val buffered = readSubsampled(bytes, targetWidthPx) ?: return null
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
