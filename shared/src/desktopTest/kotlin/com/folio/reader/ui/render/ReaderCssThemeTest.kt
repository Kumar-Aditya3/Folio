package com.folio.reader.ui.render

import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.Theme
import kotlin.test.Test
import kotlin.test.assertTrue

/**
 * The reader theme contract, pinned per preset and per formatting mode:
 *
 * 1. **Paper & ink always apply** — every mode including ORIGINAL emits the
 *    theme's background and primaryText with `!important` (the old ORIGINAL
 *    branch gated every colour rule off and its bare `html,body` rule lost to
 *    publisher CSS: zero theme effect).
 * 2. The heading, link and selection rules are always present.
 * 3. Non-original modes also force ink on the widened element list (td, th,
 *    figcaption, pre, code, …) and wash backgrounds transparent on the
 *    widened container list.
 * 4. ORIGINAL must NOT emit typography/layout forcing (font-family, size,
 *    alignment) — the publisher keeps those.
 */
class ReaderCssThemeTest {

    private fun sheet(settings: ReaderSettings): String = ReaderCss.styleSheet(
        settings = settings,
        pagedCols = 0,
        fontStack = { it },
        continuousCss = "",
    )

    private fun hex(argb: Int): String {
        val s = argb.toUInt().toString(16).padStart(8, '0')
        return "#" + s.substring(2)
    }

    private fun contrastRatio(a: Int, b: Int): Double {
        fun channel(v: Int): Double {
            val c = (v and 0xFF) / 255.0
            return if (c <= 0.03928) c / 12.92 else Math.pow((c + 0.055) / 1.055, 2.4)
        }
        fun lum(argb: Int): Double =
            0.2126 * channel(argb shr 16) + 0.7152 * channel(argb shr 8) + 0.0722 * channel(argb)
        val l1 = lum(a)
        val l2 = lum(b)
        return (maxOf(l1, l2) + 0.05) / (minOf(l1, l2) + 0.05)
    }

    private val modes = listOf(
        FormattingMode.ORIGINAL,
        FormattingMode.HYBRID,
        FormattingMode.NORMALIZED,
    )

    @Test
    fun `every preset paints paper and ink with important in every mode`() {
        for (theme in Theme.PICKER) {
            for (mode in modes) {
                val css = sheet(ReaderSettings(themeId = theme.id, formattingMode = mode))
                val bg = "background:${hex(theme.background)} !important"
                val ink = "color:${hex(theme.primaryText)} !important"
                assertTrue(
                    bg in css,
                    "${theme.id}/$mode must force the background: missing `$bg`",
                )
                assertTrue(
                    ink in css,
                    "${theme.id}/$mode must force the primary ink: missing `$ink`",
                )
            }
        }
    }

    @Test
    fun `heading link and selection rules are always emitted`() {
        for (theme in Theme.PICKER) {
            for (mode in modes) {
                val css = sheet(ReaderSettings(themeId = theme.id, formattingMode = mode))
                assertTrue(
                    "h1,h2,h3,h4,h5,h6{color:${hex(theme.headingText)} !important;}" in css,
                    "${theme.id}/$mode missing heading ink",
                )
                assertTrue(
                    "a[href^=\"http\"],a[href^=\"mailto\"]{color:${hex(theme.link)} !important;}" in css,
                    "${theme.id}/$mode missing link ink",
                )
                assertTrue(
                    "::selection{background:${hex(theme.selection)} !important" in css,
                    "${theme.id}/$mode missing selection rule",
                )
            }
        }
    }

    @Test
    fun `ink is forced on the widened element list in non-original modes`() {
        val css = sheet(ReaderSettings(themeId = "white", formattingMode = FormattingMode.HYBRID))
        for (element in listOf(
            "body td", "body th", "body figcaption", "body pre", "body code",
            "body em", "body strong", "body small", "body aside", "body summary",
        )) {
            assertTrue("$element," in css || "{$element" in css || "$element{" in css,
                "element $element missing from the ink force list")
        }
    }

    @Test
    fun `the background wash covers tables and asides in non-original modes`() {
        val css = sheet(ReaderSettings(themeId = "white", formattingMode = FormattingMode.HYBRID))
        for (element in listOf(
            "body table", "body td", "body th", "body aside", "body blockquote",
            "body pre", "body header", "body nav", "body ul", "body main", "body dl",
        )) {
            assertTrue(element in css, "element $element missing from the background wash")
        }
        // The wash must not touch the highlight vehicle or artwork.
        assertTrue("body mark" !in css.split("{background-color:transparent")[0].substringAfterLast("}"),
            "mark must not be washed transparent")
    }

    @Test
    fun `original keeps publisher typography but still paints colours`() {
        val css = sheet(ReaderSettings(themeId = "sepia", formattingMode = FormattingMode.ORIGINAL))
        // Colours: yes.
        assertTrue("background:${hex(0xFFF5E6C8.toInt())} !important" in css)
        assertTrue("color:${hex(0xFF3E2810.toInt())} !important" in css)
        // Typography/layout: no — the publisher owns those in ORIGINAL.
        assertTrue("font-size" !in css, "ORIGINAL must not force font-size")
        assertTrue("text-align" !in css, "ORIGINAL must not force text-align")
        assertTrue("font-family" !in css, "ORIGINAL must not force font-family")
    }

    @Test
    fun `non-original modes enable variable-font optical sizing and modern wrapping`() {
        for (mode in listOf(FormattingMode.HYBRID, FormattingMode.NORMALIZED)) {
            val css = sheet(ReaderSettings(themeId = "paper", formattingMode = mode))
            assertTrue(
                "font-optical-sizing:auto !important;" in css,
                "$mode must enable optical sizing so variable faces grade against font-size",
            )
            assertTrue(
                "text-rendering:optimizeLegibility !important;" in css,
                "$mode must turn on kerning/ligatures",
            )
            assertTrue(
                "body p,body li,body dd,body blockquote{text-wrap:pretty !important;}" in css,
                "$mode must apply text-wrap:pretty to body prose",
            )
            assertTrue(
                "text-wrap:balance !important;" in css,
                "$mode must balance short headings/captions",
            )
        }
    }

    @Test
    fun `original mode leaves modern typography to the publisher`() {
        val css = sheet(ReaderSettings(themeId = "sepia", formattingMode = FormattingMode.ORIGINAL))
        assertTrue("font-optical-sizing" !in css, "ORIGINAL must not force optical sizing")
        assertTrue("text-wrap" !in css, "ORIGINAL must not force line-wrapping")
        assertTrue("text-rendering" !in css, "ORIGINAL must not force text-rendering")
    }

    @Test
    fun `secondary ink and dividers use the theme palette`() {
        val theme = Theme.getPreset("paper")
        val css = sheet(ReaderSettings(themeId = "paper", formattingMode = FormattingMode.HYBRID))
        assertTrue("figcaption,body small{color:${hex(theme.secondaryText)} !important;}" in css ||
            "color:${hex(theme.secondaryText)} !important" in css,
            "secondaryText must reach figcaption/small")
        assertTrue("hr{background:${hex(theme.divider)} !important" in css,
            "divider must paint hr")
    }

    @Test
    fun `every preset keeps primary ink on paper at 4_5 to 1 or better`() {
        for (theme in Theme.PICKER) {
            val ratio = contrastRatio(theme.primaryText, theme.background)
            assertTrue(
                ratio >= 4.5,
                "${theme.id}: primaryText on background is only $ratio:1",
            )
        }
    }

    @Test
    fun `near-duplicate presets are visibly distinct`() {
        // Pairwise within a polarity: backgrounds must differ enough that the
        // swatches/pages are distinguishable. The floor is a channel-sum
        // distance (≈3/channel) — the "visually identical" zone the near-
        // duplicate audit (gray/white, graphite/dark, …) was fixing. Pairs
        // farther apart than that differ by hue or lightness, which reads as
        // distinct on a full page even when the raw distance is modest.
        fun distance(a: Int, b: Int): Int {
            fun ch(v: Int, shift: Int) = (v shr shift) and 0xFF
            return kotlin.math.abs(ch(a, 16) - ch(b, 16)) +
                    kotlin.math.abs(ch(a, 8) - ch(b, 8)) +
                    kotlin.math.abs(ch(a, 0) - ch(b, 0))
        }
        val presets = Theme.PICKER
        for (i in presets.indices) {
            for (j in i + 1 until presets.size) {
                val a = presets[i]
                val b = presets[j]
                if (a.isDark != b.isDark) continue
                val d = distance(a.background, b.background)
                assertTrue(
                    d >= 10,
                    "${a.id} and ${b.id} are visually identical backgrounds (distance $d)",
                )
            }
        }
    }
}
