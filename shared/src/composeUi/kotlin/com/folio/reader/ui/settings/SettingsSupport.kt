package com.folio.reader.ui.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment
import com.folio.reader.settings.Theme
import com.folio.reader.ui.components.systemFontFamily
import com.folio.reader.ui.theme.UiFonts

@Composable
fun SettingsLivePreview(settings: ReaderSettings) {
    val theme = settings.customTheme ?: Theme.getPreset(settings.themeId)
    val fontFamily = readerFontFamily(settings, settings.fontFamily, settings.fontWeight)
    val align = when (settings.alignment) {
        TextAlignment.CENTER -> TextAlign.Center
        TextAlignment.JUSTIFIED -> TextAlign.Justify
        else -> TextAlign.Start
    }
    val shape = RoundedCornerShape(16.dp)
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp)
            .clip(shape)
            .background(Color(theme.background))
            .border(1.dp, Color(theme.divider), shape)
            .padding(
                horizontal = settings.margins.left.coerceIn(0f, 28f).dp,
                vertical = 18.dp
            )
    ) {
        Text(
            text = "Chapter One",
            style = TextStyle(
                fontFamily = fontFamily,
                fontSize = (settings.fontSize * 1.3f).sp,
                fontWeight = FontWeight.Bold,
                letterSpacing = settings.letterSpacing.sp,
                color = Color(theme.headingText),
                textAlign = align
            )
        )
        Spacer(Modifier.height(8.dp))
        val bodyStyle = TextStyle(
            fontFamily = fontFamily,
            fontSize = settings.fontSize.sp,
            lineHeight = (settings.fontSize * settings.lineHeight).sp,
            fontWeight = FontWeight(settings.fontWeight),
            letterSpacing = settings.letterSpacing.sp,
            color = Color(theme.primaryText),
            textAlign = align
        )
        Text(
            text = withWordSpacing(
                "The reading room was quiet but for the rain, and the lamplight had " +
                    "made a small country of its own on the table, where the pages waited " +
                    "for someone to turn them.",
                settings.wordSpacing
            ),
            style = bodyStyle
        )
        // Paragraph spacing is a multiple of the text size, exactly as the reader
        // applies it — a second paragraph is the only way the control can show.
        Spacer(Modifier.height((settings.fontSize * settings.paragraphSpacing).dp))
        Text(
            text = withWordSpacing(
                "She turned one, and then another, and the evening went on without her.",
                settings.wordSpacing
            ),
            style = bodyStyle
        )
        Spacer(Modifier.height(12.dp))
        Text(
            text = "${readerFontLabel(settings, settings.fontFamily)} · " +
                "${settings.fontSize.toInt()}sp · " +
                "${"%.1f".format(settings.lineHeight)} line · ${theme.name}",
            style = TextStyle(fontSize = 11.sp, color = Color(theme.secondaryText))
        )
    }
}

/**
 * Compose has no `word-spacing`, so the preview widens the gaps themselves:
 * each space carries the extra em as its own letter spacing. Matches the sign
 * and rough magnitude of the reader's `word-spacing:{n}em`, which is all a
 * preview needs — without this the word-spacing slider looked dead.
 */
private fun withWordSpacing(text: String, em: Float): AnnotatedString {
    if (em == 0f) return AnnotatedString(text)
    return buildAnnotatedString {
        append(text)
        var index = text.indexOf(' ')
        while (index >= 0) {
            addStyle(SpanStyle(letterSpacing = em.em), index, index + 1)
            index = text.indexOf(' ', index + 1)
        }
    }
}

/** Renders an enum as a readable word for dropdowns without touching the persisted name. */
internal fun Enum<*>.settingsLabel(): String =
    name.lowercase().replaceFirstChar { it.uppercaseChar() }

/**
 * The reader font list every settings surface offers: the bundled/imported
 * faces first (they render with their real glyphs), then the generic names the
 * platform can resolve. Single source of truth — the typography panel and the
 * in-reader panel used to keep two drifting copies.
 */
internal fun readerFontOptions(settings: ReaderSettings): List<String> =
    (settings.customFonts.map { it.name } + BASE_READER_FONTS).distinct()

private val BASE_READER_FONTS = listOf(
    "Literata", "Merriweather", "Georgia", "EB Garamond", "Lora",
    "Open Sans", "Inter", "Noto Serif", "Serif", "Sans Serif", "Monospace"
)

/** The display name for a persisted font key — custom fonts show their real family. */
internal fun readerFontLabel(settings: ReaderSettings, key: String): String =
    settings.customFonts.firstOrNull { it.name == key }?.familyName ?: key

/**
 * Resolves a persisted font key to something that actually *looks* like the
 * font. A custom or bundled face loads from its own file; anything else goes
 * through the platform's family lookup. Without the file path first, every
 * bundled and every serif option previewed as the same generic serif, which is
 * what made the typeface picker feel broken.
 */
@Composable
internal fun readerFontFamily(settings: ReaderSettings, key: String, weight: Int = 400): FontFamily? {
    val custom = settings.customFonts.firstOrNull { it.name == key }
    if (custom != null) {
        UiFonts.fromFile(custom.fileName, weight)?.let { return it }
        return systemFontFamily(custom.familyName)
    }
    return systemFontFamily(key)
}
