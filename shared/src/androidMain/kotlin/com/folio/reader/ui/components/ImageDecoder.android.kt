package com.folio.reader.ui.components

import android.graphics.BitmapFactory
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.text.font.FontFamily

actual fun decodeCoverImage(bytes: ByteArray): ImageBitmap? {
    return try {
        // A local CBZ's first page can be a giant strip; covers render at thumbnail
        // size, so cap the long edge instead of decoding (and drawing) hundreds of MB.
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, bounds)
        var sample = 1
        while (maxOf(bounds.outWidth, bounds.outHeight) / (sample * 2) >= 2048) sample *= 2
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: Exception) {
        null
    }
}

/** Never decode taller than this: it sits under common GL max-texture limits. */
private const val MAX_DECODE_HEIGHT = 16384

/** Same reason as the height cap, for wide pages. */
private const val MAX_DECODE_WIDTH = 8192

/**
 * Total-pixel budget per page (~64MB as ARGB_8888). hwui refuses to draw bitmaps
 * over ~100MB ("Canvas: trying to draw too large bitmap") — a page that is both
 * wide and tall (e.g. 6000×9000 = 216MB) passed the single-axis caps and crashed
 * the reader as soon as it was drawn.
 */
private const val MAX_DECODE_PIXELS = 16_777_216L // 4096²

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
            while (srcW / sample > MAX_DECODE_WIDTH) sample *= 2
            while ((srcW.toLong() / sample) * (srcH.toLong() / sample) > MAX_DECODE_PIXELS) sample *= 2
        }
        val opts = BitmapFactory.Options().apply { inSampleSize = sample }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, opts)?.asImageBitmap()
    } catch (_: Exception) {
        // No unsampled fallback: full-decoding a huge page is exactly what hwui
        // cannot draw. A null shows "Page failed to load" instead of crashing.
        null
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
