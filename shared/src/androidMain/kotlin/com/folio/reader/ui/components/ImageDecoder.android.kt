package com.folio.reader.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily

actual fun decodeCoverImage(bytes: ByteArray): ImageBitmap? {
    return try {
        val bitmap = BitmapFactory.decodeByteArray(bytes, 0, bytes.size) ?: return null
        bitmap.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/** Never decode taller than this: it sits under common GL max-texture limits. */
private const val MAX_DECODE_HEIGHT = 16384

actual fun decodePageImage(bytes: ByteArray, targetWidthPx: Int): ImageBitmap? {
    return try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        val srcW = bounds.outWidth
        val srcH = bounds.outHeight
        var sample = 1
        if (srcW > 0 && srcH > 0 && targetWidthPx > 0) {
            // Subsample while the decoded width would still cover the target.
            while (srcW / (sample * 2) >= targetWidthPx) sample *= 2
            // Extremely tall strips: keep the decoded height sane without shrinking
            // the width further than the height cap forces.
            while (srcH / sample > MAX_DECODE_HEIGHT) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: Exception) {
        // Fall back to a plain decode rather than failing the page outright.
        decodeCoverImage(bytes)
    }
}

/**
 * Android ships Roboto + serif/monospace system families. Named families like
 * Georgia/Literata aren't installed, so map to the closest system family the
 * platform actually provides.
 */
actual fun systemFontFamily(name: String): FontFamily? {
    val f = name.lowercase()
    val result = when {
        f.contains("mono") || f.contains("jetbrains") || f.contains("code") || 
            f.contains("courier") -> FontFamily.Monospace
        f.contains("serif") || f.contains("literata") || f.contains("merriweather") ||
            f.contains("garamond") || f.contains("lora") || f.contains("georgia") ||
            f.contains("times") || f.contains("playfair") || f.contains("crimson") ||
            f.contains("baskerville") || (f.contains("noto") && !f.contains("sans")) ||
            f.contains("pt serif") || f.contains("source serif") || f.contains("dm serif") ||
            f.contains("calluna") ->
            FontFamily.Serif
        f.contains("roboto") || f.contains("inter") || f.contains("open sans") ||
            f.contains("nunito") || f.contains("poppins") || f.contains("raleway") ||
            f.contains("montserrat") || f.contains("ubuntu") || f.contains("fira sans") ||
            f.contains("work sans") || f.contains("source sans") || f.contains("space grotesk") ||
            f.contains("sans") || f.contains("arial") || f.contains("helvetica") ||
            f.contains("comfortaa") || f.contains("noto sans") ->
            FontFamily.SansSerif
        else -> {
            println("Font system (Android): No match for '$name', using default")
            FontFamily.Default
        }
    }
    
    if (result != FontFamily.Default || f.contains("default") || f.contains("system")) {
        println("Font system (Android): Mapped '$name' to generic family")
    }
    
    return result
}
