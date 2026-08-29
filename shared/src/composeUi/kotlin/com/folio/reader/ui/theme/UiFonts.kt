package com.folio.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * The two faces the interface is built from.
 *
 * premium is not many fonts — it is two faces used with total discipline. Fraunces
 * (a soft, optical display serif) carries the voice: the wordmark, book titles,
 * screen headers and big stat numbers, plus quotations in italic. Manrope carries
 * everything functional: rows, buttons, labels, chips. Every step between them is
 * made with size, weight and tracking rather than by adding a third face.
 *
 * Both are variable fonts, so each weight is a real instance (selected on the `wght`
 * axis) instead of a synthesised bold, and display sizes opt into a larger `opsz`
 * so Fraunces keeps its high contrast when it is big.
 */
object UiFonts {
    private const val DISPLAY_FILE = "fraunces_variable.ttf"
    private const val DISPLAY_ITALIC_FILE = "fraunces_italic_variable.ttf"
    private const val TEXT_FILE = "manrope_variable.ttf"

    /** Directory the faces are extracted to — see BundledFonts.ensureInstalled. */
    @Volatile
    private var fontsDir: File? = null

    private val cache = ConcurrentHashMap<String, FontFamily?>()

    /** Called from each platform's bootstrap before the first frame is composed. */
    fun install(dir: File) {
        fontsDir = dir
        cache.clear()
    }

    /** Fraunces. [opticalSize] only changes the letterforms at display sizes. */
    fun display(weight: Int = 600, italic: Boolean = false, opticalSize: Float = 0f): FontFamily =
        load(if (italic) DISPLAY_ITALIC_FILE else DISPLAY_FILE, weight, italic, opticalSize)

    /** Manrope. */
    fun text(weight: Int = 400): FontFamily = load(TEXT_FILE, weight, false, 0f)

    private fun load(fileName: String, weight: Int, italic: Boolean, optical: Float): FontFamily {
        val key = "$fileName:$weight:$italic:$optical"
        cache[key]?.let { return it }
        if (cache.containsKey(key)) return FontFamily.Default
        val file = fontsDir?.resolve(fileName)?.takeIf { it.length() > 0L }
        val family = file?.let {
            runCatching { fileFontFamily(it.absolutePath, weight, italic, optical) }.getOrNull()
        }
        cache[key] = family
        return family ?: FontFamily.Default
    }
}

/**
 * One weight/shape instance of a variable font file, as a [FontFamily].
 * Returns null when the platform cannot load it, in which case the caller falls
 * back to the system default rather than shipping a broken glyph run.
 */
expect fun fileFontFamily(path: String, weight: Int, italic: Boolean, opticalSize: Float): FontFamily?
