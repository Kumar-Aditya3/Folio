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
        // ORIGINAL keeps the publisher's typography/layout; the theme still owns
        // paper & ink (always `!important` there — the only way a plain element
        // rule beats publisher class rules).
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
        // Ink on body is always forced (even in ORIGINAL) so a theme switch always
        // repaints the page; the element-level force below handles publisher rules.
        val colorCss = "color:#${theme.primaryText.rgb()} !important;"
        val hyphenCss = if (!original && settings.hyphenation) "-webkit-hyphens:auto;hyphens:auto;" else ""
        val paragraphCss = if (original) "" else
            "p{margin-top:0 !important;margin-bottom:${settings.paragraphSpacing}em !important;}"
        // The background wash: transparent on the containers that would otherwise
        // paint publisher white/black over the theme's paper. `mark`, `img`, `svg`
        // and `a` are deliberately excluded — the highlight wash, artwork and link
        // tints must survive this rule (HighlightPaint paints mark backgrounds
        // inline, which beats any stylesheet, but exclusion keeps the contract
        // explicit). Applies in every mode: ORIGINAL still owns the page's field.
        val themeBgCss =
            "body,body div,body section,body article,body figure,body table,body td,body th," +
                    "body aside,body blockquote,body pre,body header,body footer,body nav," +
                    "body ul,body ol,body main,body dl" +
                    "{background-color:transparent !important;}"
        val normalizedExtra = if (!normalized || original) "" else
            "body p,body div,body h1,body h2,body h3,body h4,body h5,body h6,body li,body blockquote" +
                    "{text-align:$align !important;$forceFamily}"
        // Publisher rules declared on elements (e.g. .calibre p{color:#000}) outrank an
        // inherited body rule, so the reader's ink goes on the elements themselves —
        // and on the full set of text-bearing elements, not just body text: publisher
        // colours used to survive on td/th/figcaption/pre/code and the like. Ink is
        // forced in every mode (the theme always owns paper & ink); fonts only when
        // the reader owns typography (HYBRID/NORMALIZED, not ORIGINAL).
        val inkElements = "body p,body div,body span,body li,body blockquote," +
                "body td,body th,body dd,body dt,body figcaption,body pre,body code," +
                "body em,body strong,body i,body b,body u,body small,body aside," +
                "body details,body summary"
        val elementForceCss =
            "$inkElements{color:#${theme.primaryText.rgb()} !important;}" +
                    (if (original) "" else
                        "$inkElements{$forceFamily}" +
                                (if (publisherFonts) "" else "body h1,body h2,body h3,body h4,body h5,body h6{font-family:$family !important;}")) +
                    (if (original) "" else "body,body p,body div,body li,body blockquote{text-indent:0 !important;}")
        // Secondary ink: figcaptions and small print sit back from body text.
        val secondaryInkCss =
            "body figcaption,body small,body dt,body caption{color:#${theme.secondaryText.rgb()} !important;}"
        // The theme's own divider colour on horizontal rules.
        val dividerCss =
            "hr{background:#${theme.divider.rgb()} !important;border-color:#${theme.divider.rgb()} !important;border:none;}"
        // A whisper of the theme's surface on block elements — HYBRID/NORMALIZED
        // only; ORIGINAL keeps the publisher's plain page furniture.
        val surfaceCss = if (original) "" else
            "body pre,body blockquote{background-color:#${theme.surface.rgb()} !important;}"

        // Paged modes cap the measure per column inside the engine, and need body
        // exactly 100vw wide for its page steps to line up.
        val capPx = if (pagedCols == 0) PageEngine.measurePx(settings.textWidth) else 0
        val widthCss = if (capPx > 0)
            "body{max-width:${capPx}px !important;margin-left:auto !important;margin-right:auto !important;}"
        else ""
        // Spread mode (two-page side-by-side): a hairline centre rule gives each
        // page its own visual frame without stealing reading space. The rule sits
        // at exactly 50vw — the boundary between the two engine columns.
        val spreadCss = if (pagedCols == 2)
            "body::after{content:'';position:fixed;top:0;bottom:0;left:50%;width:1px;" +
                    "background:${if (theme.isDark) "rgba(255,255,255,0.10)" else "rgba(0,0,0,0.08)"};" +
                    "pointer-events:none;z-index:0;}"
        else ""
        // Paged pages read bottom-heavy at equal padding (nothing anchors the eye
        // below the last line), so the bottom gap is trimmed there only.
        val pagedBottom = if (pagedCols > 0) settings.margins.bottom / 2 else settings.margins.bottom
        val pad = if (pagedCols > 0)
            "${settings.margins.top}px 0 ${pagedBottom.toInt()}px 0"
        else
            "${settings.margins.top}px ${settings.margins.right}px ${settings.margins.bottom}px ${settings.margins.left}px"

        val selectionInk = if (theme.selection.isLight()) 0xFF000000.toInt() else 0xFFFFFFFF.toInt()

        // Paper & ink are forced (`!important`) in every mode including ORIGINAL:
        // a plain html,body rule loses to even the weakest publisher class rule,
        // which is why themes used to have no effect at all in ORIGINAL mode.
        return "html,body{margin:0;padding:0;background:#${theme.background.rgb()} !important;" +
                "color:#${theme.primaryText.rgb()} !important;}" +
                "::selection{background:#${theme.selection.rgb()} !important;color:#${selectionInk.rgb()} !important;}" +
                themeBgCss +
                "body{padding:$pad$imp;$typography$alignCss$colorCss$hyphenCss}" +
                (if (pagedCols > 0)
                    PageEngine.css(settings.margins.top, pagedBottom)
                else continuousCss) +
                widthCss +
                spreadCss +
                paragraphCss +
                normalizedExtra +
                elementForceCss +
                secondaryInkCss +
                dividerCss +
                surfaceCss +
                "h1,h2,h3,h4,h5,h6{color:#${theme.headingText.rgb()} !important;}" +
                HighlightPaint.css +
                "img{max-width:100%;height:auto;break-inside:avoid;}" +
                "a{color:inherit;text-decoration:none;}" +
                "a[href^=\"http\"],a[href^=\"mailto\"]{color:#${theme.link.rgb()} !important;}"
    }
}
