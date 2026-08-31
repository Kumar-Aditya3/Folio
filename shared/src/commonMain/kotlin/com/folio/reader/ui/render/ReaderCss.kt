package com.folio.reader.ui.render

import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.TextAlignment

/**
 * The reader stylesheet, applied by both the Android WebView and the desktop JCEF
 * surface. It replaces two hand-written per-platform builders that each listed
 * only the properties they happened to know about: paragraph spacing was emitted
 * by neither, word spacing and the line-length cap by desktop only, heading ink
 * by desktop only, and `useEmbeddedFonts` by nothing at all — so a preference
 * could save, reload and still never reach the page.
 *
 * Every [ReaderSettings] field that paints text is listed here once. A new one
 * belongs in this file, not in a platform surface.
 *
 * [continuousCss] carries the platform's own scrolling-mode layout — the desktop
 * surface reveals the page through an opacity step, the WebView has to break the
 * publisher's height rules — which is the only genuinely platform-bound piece.
 */
object ReaderCss {

    private fun Int.rgb(): String = toUInt().toString(16).padStart(8, '0').drop(2)

    /** Weighted luminance, good enough to pick readable ink over a flat selection wash. */
    private fun Int.isLight(): Boolean =
        (shr(16) and 0xFF) * 0.299 + (shr(8) and 0xFF) * 0.587 + (this and 0xFF) * 0.114 > 140

    fun styleSheet(
        settings: ReaderSettings,
        pagedCols: Int,
        fontStack: (String) -> String,
        continuousCss: String
    ): String {
        val theme = settings.customTheme
            ?: com.folio.reader.settings.Theme.getPreset(settings.themeId)
        val original = settings.formattingMode == FormattingMode.ORIGINAL
        val normalized = settings.formattingMode == FormattingMode.NORMALIZED
        // In ORIGINAL the publisher owns the sheet, so the reader states nothing.
        val imp = if (original) "" else " !important"
        val align = when (settings.alignment) {
            TextAlignment.CENTER -> "center"
            TextAlignment.JUSTIFIED -> "justify"
            else -> "left"
        }
        val requested = settings.customFonts.firstOrNull { it.name == settings.fontFamily }?.familyName
            ?: settings.fontFamily
        val family = fontStack(requested)
        // A book's embedded faces are unreachable while the reader forces its own
        // family onto every element; dropping that force is all the toggle means.
        val publisherFonts = settings.useEmbeddedFonts && !original
        val forceFamily = if (publisherFonts) "" else "font-family:$family !important;"

        // Spacing sliders are authored in em so their whole travel is visible at
        // any text size; a px value of 0.3 would move nothing.
        val typography = if (original) "" else
            "font-family:$family$imp;font-size:${settings.fontSize}px$imp;" +
                    "font-weight:${settings.fontWeight}$imp;line-height:${settings.lineHeight}$imp;" +
                    "letter-spacing:${settings.letterSpacing}em$imp;word-spacing:${settings.wordSpacing}em$imp;"
        val alignCss = if (original) "" else "text-align:$align$imp;"
        val colorCss = if (original) "" else "color:#${theme.primaryText.rgb()}$imp;"
        val hyphenCss = if (!original && settings.hyphenation) "-webkit-hyphens:auto;hyphens:auto;" else ""
        val paragraphCss = if (original) "" else
            "p{margin-top:0 !important;margin-bottom:${settings.paragraphSpacing}em !important;}"
        val themeBgCss = if (original) "" else
            "body,body div,body section,body article,body figure{background-color:transparent !important;}"
        val normalizedExtra = if (!normalized || original) "" else
            "body p,body div,body h1,body h2,body h3,body h4,body h5,body h6,body li,body blockquote" +
                    "{text-align:$align !important;$forceFamily}"
        // Publisher rules declared on elements (e.g. .calibre p{color:#000}) outrank an
        // inherited body rule, so the reader's ink goes on the elements themselves.
        val elementForceCss = if (original) "" else
            "body p,body div,body span,body li,body blockquote{color:#${theme.primaryText.rgb()} !important;$forceFamily}" +
                    (if (publisherFonts) "" else "body h1,body h2,body h3,body h4,body h5,body h6{font-family:$family !important;}") +
                    "body,body p,body div,body li,body blockquote{text-indent:0 !important;}"

        // Paged modes cap the measure per column inside the engine, and need body
        // exactly 100vw wide for its page steps to line up.
        val capPx = if (pagedCols == 0) PageEngine.measurePx(settings.textWidth) else 0
        val widthCss = if (capPx > 0)
            "body{max-width:${capPx}px !important;margin-left:auto !important;margin-right:auto !important;}"
        else ""
        val pad = if (pagedCols > 0)
            "${settings.margins.top}px 0 ${settings.margins.bottom}px 0"
        else
            "${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px"

        val selectionInk = if (theme.selection.isLight()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

        return "html,body{margin:0;padding:0;background:#${theme.background.rgb()};color:#${theme.primaryText.rgb()};}" +
                "::selection{background:#${theme.selection.rgb()};color:#${selectionInk.rgb()};}" +
                themeBgCss +
                "body{padding:$pad$imp;$typography$alignCss$colorCss$hyphenCss}" +
                (if (pagedCols > 0)
                    PageEngine.css(pagedCols, settings.margins.top, settings.margins.bottom, "#${theme.background.rgb()}")
                else continuousCss) +
                widthCss +
                paragraphCss +
                normalizedExtra +
                elementForceCss +
                "h1,h2,h3,h4,h5,h6{color:#${theme.headingText.rgb()}$imp;}" +
                HighlightPaint.css +
                "img{max-width:100%;height:auto;break-inside:avoid;}" +
                "a{color:inherit;text-decoration:none;}" +
                "a[href^=\"http\"],a[href^=\"mailto\"]{color:#${theme.link.rgb()}$imp;}"
    }
}
