package com.folio.reader.ui.theme

import androidx.compose.ui.text.font.FontFamily
import java.io.File
import java.util.concurrent.ConcurrentHashMap

enum class FontTheme(
    val id: String,
    val label: String,
    val displayFile: String,
    val displayItalicFile: String?,
    val textFile: String,
) {
    CLASSIC(
        "classic", "Classic",
        "fraunces_variable.ttf", "fraunces_italic_variable.ttf", "manrope_variable.ttf"
    ),
    EDITORIAL(
        "editorial", "Editorial",
        "calluna_regular.otf", null, "manrope_variable.ttf"
    ),
    SOFT(
        "soft", "Soft",
        "comfortaa_variable.ttf", null, "manrope_variable.ttf"
    ),
    STORYBOOK(
        "storybook", "Storybook",
        "shancalluna_regular.ttf", null, "comfortaa_variable.ttf"
    );

    companion object {
        fun byId(id: String): FontTheme = entries.firstOrNull { it.id == id } ?: CLASSIC
    }
}

object UiFonts {
    @Volatile
    private var fontsDir: File? = null

    private val cache = ConcurrentHashMap<String, FontFamily?>()

    fun install(dir: File) {
        fontsDir = dir
        cache.clear()
    }

    fun display(
        theme: FontTheme = FontTheme.CLASSIC,
        weight: Int = 600,
        italic: Boolean = false,
        opticalSize: Float = 0f
    ): FontFamily {
        val file = if (italic && theme.displayItalicFile != null) theme.displayItalicFile else theme.displayFile
        return load(file, weight, italic, opticalSize)
    }

    fun text(theme: FontTheme = FontTheme.CLASSIC, weight: Int = 400): FontFamily =
        load(theme.textFile, weight, false, 0f)

    private fun load(fileName: String, weight: Int, italic: Boolean, optical: Float): FontFamily {
        val key = "$fileName:$weight:$italic:$optical"
        cache[key]?.let { return it }
        val file = fontsDir?.resolve(fileName)?.takeIf { it.length() > 0L }
        val family = file?.let {
            runCatching { fileFontFamily(it.absolutePath, weight, italic, optical) }.getOrNull()
        }
        // A missing or unloadable file falls back to Default on every load; the
        // cache is a ConcurrentHashMap and cannot hold null sentinels.
        if (family != null) cache[key] = family
        return family ?: FontFamily.Default
    }
}

expect fun fileFontFamily(path: String, weight: Int, italic: Boolean, opticalSize: Float): FontFamily?
