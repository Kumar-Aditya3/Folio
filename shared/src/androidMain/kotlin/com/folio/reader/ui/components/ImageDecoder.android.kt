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
