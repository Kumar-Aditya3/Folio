package com.folio.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.platform.Typeface
import org.jetbrains.skia.FontMgr
import org.jetbrains.skia.FontVariation
import java.util.concurrent.ConcurrentHashMap

/**
 * Desktop: skia loads each variable file once, then `makeClone` selects the exact
 * `wght`/`opsz` instance, so a SemiBold heading is the designer's SemiBold rather
 * than a synthesised smear. The base face is retained because clones borrow it.
 */
private val baseFaces = ConcurrentHashMap<String, org.jetbrains.skia.Typeface?>()

private fun baseFace(path: String): org.jetbrains.skia.Typeface? {
    if (baseFaces.containsKey(path)) return baseFaces[path]
    val loaded = runCatching { FontMgr.default.makeFromFile(path, 0) }.getOrNull()
    baseFaces[path] = loaded
    return loaded
}

actual fun fileFontFamily(
    path: String,
    weight: Int,
    italic: Boolean,
    opticalSize: Float
): FontFamily? {
    val base = baseFace(path) ?: return null
    return try {
        val axes = listOfNotNull(
            FontVariation("wght", weight.toFloat()),
            if (opticalSize > 0f) FontVariation("opsz", opticalSize) else null
        )
        FontFamily(Typeface(base.makeClone(axes.toTypedArray(), 0) ?: base))
    } catch (e: Throwable) {
        null
    }
}
