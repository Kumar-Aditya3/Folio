package com.folio.reader

import androidx.compose.runtime.*
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.text.TextMeasurer
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import com.folio.reader.experience.*
import com.folio.reader.ui.theme.*
import java.io.File
import javax.swing.SwingUtilities
import kotlin.test.*

class BeautifulExportsRenderTest {
    @Test fun quoteInkFollowsEveryAppPaletteWithReadableContrast() {
        for (theme in AppPalette.entries) {
            val colors = folioRenderedColors(theme.colors)
            val atmosphere = atmosphereFor(colors)
            val palette = CardPalette(atmosphere.raisedFill, colors.onSurface, colors.primary, atmosphere.fieldBottom)
            val first = palette.quoteInk.luminance()
            val second = palette.paper.luminance()
            val ratio = (maxOf(first, second) + .05f) / (minOf(first, second) + .05f)
            assertTrue(ratio >= 4.49f, "${theme.id} quote contrast was $ratio")
            val accentRatio = (maxOf(palette.accent.luminance(), second) + .05f) /
                (minOf(palette.accent.luminance(), second) + .05f)
            if (accentRatio >= 4.5f) assertEquals(colors.primary, palette.quoteInk)
        }
        val green = CardPalette.Print.copy(accent = Color(0xff286453))
        val violet = green.copy(accent = Color(0xff65428d))
        assertNotEquals(green.quoteInk, violet.quoteInk)
    }

    @OptIn(ExperimentalComposeUiApi::class)
    @Test fun renderEveryTemplateAndFormatWithActualBundledFonts() {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait {
            try {
                val font = requireNotNull(javaClass.classLoader.getResource("fonts/fraunces_variable.ttf"))
                UiFonts.install(File(font.toURI()).parentFile)
                var measurer: TextMeasurer? = null
                val scene = ImageComposeScene(1, 1) { measurer = rememberTextMeasurer() }
                try {
                    scene.render(0).close()
                    val measure = requireNotNull(measurer)
                    val directory = File("build/visual-review/beautiful-exports").apply { mkdirs() }
                    val source = ExportSource("test", "book", "The Art of Noticing", "A Folio reader",
                        "Some passages ask us to slow down. We keep them, and the world becomes a little larger.",
                        "Chapter 4 · A quiet afternoon", note = "The part I want to remember: make room for attention.")
                    for (template in CardTemplate.entries) for (format in CardFormat.entries) for (dark in listOf(false,true)) {
                        val options = QuoteCardOptions(template, format, note = true)
                        val pages = paginateQuoteCards(listOf(source), options, measure)
                        assertTrue(pages.isNotEmpty())
                        assertEquals(source.text, pages.filter { it.text.isNotEmpty() }.joinToString("") { it.text })
                        assertEquals(source.note, pages.mapNotNull { it.note }.joinToString(""))
                        val palette = if (dark) CardPalette(Color(0xff222c2b), Color(0xffeef2e8), Color(0xffb4cbb8),Color(0xff162422)) else CardPalette.Print
                        val image = renderQuoteCard(pages.first(), options, measure, palette)
                        assertEquals(1080, image.width); assertEquals(format.height, image.height)
                        File(directory, "${template.name.lowercase()}-${format.name.lowercase()}-${if (dark) "dark" else "light"}.png").writeBytes(encodeCardPng(image))
                    }
                    // A short quotation should still have a deliberate composition, even on a story card.
                    val short = source.copy(text = "Make room for attention.", note = null)
                    for (template in CardTemplate.entries) {
                        val options = QuoteCardOptions(template, CardFormat.STORY)
                        File(directory, "${template.name.lowercase()}-short-story.png").writeBytes(encodeCardPng(
                            renderQuoteCard(paginateQuoteCards(listOf(short), options, measure).single(), options, measure, CardPalette.Print)))
                        val inkOptions = options.copy(format = CardFormat.SQUARE, inkFriendly = true)
                        File(directory, "${template.name.lowercase()}-ink-friendly.png").writeBytes(encodeCardPng(
                            renderQuoteCard(paginateQuoteCards(listOf(source), inkOptions, measure).first(), inkOptions, measure, CardPalette.Print)))
                    }
                    for (theme in listOf(AppPalette.COOL, AppPalette.PINK, AppPalette.ESPRESSO)) {
                        val colors = folioRenderedColors(theme.colors)
                        val atmosphere = atmosphereFor(colors)
                        val palette = CardPalette(atmosphere.raisedFill, colors.onSurface, colors.primary, atmosphere.fieldBottom)
                        val options = QuoteCardOptions(CardTemplate.ATMOSPHERE, CardFormat.PORTRAIT)
                        File(directory, "current-theme-${theme.id}.png").writeBytes(encodeCardPng(
                            renderQuoteCard(paginateQuoteCards(listOf(source), options, measure).first(), options, measure, palette)))
                    }
                    val multilingual = source.copy(text = "雨の音を聞きながら、本を読む。\n\nहमें यह पंक्ति याद है।\n\nهذا وقت للقراءة والتأمل.\n\nA thought 🙂 worth keeping.")
                    val pages = paginateQuoteCards(listOf(multilingual), QuoteCardOptions(), measure)
                    assertEquals(multilingual.text, pages.joinToString("") { it.text })
                    pages.forEachIndexed { i, page -> File(directory,"multilingual-$i.png").writeBytes(encodeCardPng(renderQuoteCard(page, QuoteCardOptions(),measure,CardPalette.Print))) }
                    val long = source.copy(text = List(60) { source.text }.joinToString("\n\n"))
                    val overflow = paginateQuoteCards(listOf(long), QuoteCardOptions(format = CardFormat.SQUARE), measure)
                    assertTrue(overflow.size > 1)
                    assertEquals(long.text, overflow.joinToString("") { it.text })
                    val fitSource = source.copy(text = List(6) { source.text }.joinToString(" "), note = null)
                    val longAttribution = source.copy(
                        title = "The Art of Noticing: A Reader's Collection of Observations, Small Discoveries, and the Passages We Return To",
                        author = "Alexandra Longname, Christopher Anotherlongname, and the Folio Reading Circle",
                        chapter = "Chapter Four: On the Quiet Practice of Attention and Everything We Find When We Slow Down and Read Again")
                    for (template in CardTemplate.entries) for (format in CardFormat.entries) {
                        val options = QuoteCardOptions(template, format)
                        val fitted = paginateQuoteCards(listOf(fitSource), options, measure)
                        assertEquals(1, fitted.size, "$template $format split instead of fitting")
                        assertEquals(fitSource.text, fitted.single().text)
                        val layout = layoutQuoteCard(fitted.single(), options, measure)
                        assertTrue(layout.fits); assertTrue(layout.fontSize >= 30)
                        assertTrue(layout.contentBottom <= layout.contentArea.bottom + .01f)
                        File(directory, "fitted-${template.name.lowercase()}-${format.name.lowercase()}.png").writeBytes(encodeCardPng(
                            renderQuoteCard(fitted.single(), options, measure, CardPalette.Print)))
                        val attributionPage = paginateQuoteCards(listOf(longAttribution), options.copy(cover = true, note = true), measure).first()
                        val footerLayout = layoutQuoteCard(attributionPage, options.copy(cover = true, note = true), measure)
                        assertTrue(footerLayout.fits)
                        footerLayout.frame.attribution.forEach { line ->
                            assertTrue(line.position.y + line.text.size.height <= footerLayout.frame.brandIcon.top - 25f,
                                "$template attribution overlaps the app icon")
                            assertFalse(line.text.hasVisualOverflow)
                        }
                        assertTrue(footerLayout.contentBottom < footerLayout.frame.footerRule)
                        File(directory, "footer-${template.name.lowercase()}-${format.name.lowercase()}.png").writeBytes(encodeCardPng(
                            renderQuoteCard(attributionPage, options.copy(cover = true, note = true), measure, CardPalette.Print)))
                    }
                    val combinedSource = source.copy(text = List(4) { source.text }.joinToString(" "))
                    val combinedOptions = QuoteCardOptions(format = CardFormat.SQUARE, note = true)
                    val combined = paginateQuoteCards(listOf(combinedSource), combinedOptions, measure)
                    assertEquals(1, combined.size); assertEquals(combinedSource.note, combined.single().note)
                    assertEquals(2, paginateQuoteCards(listOf(source, source.copy(id = "second")), QuoteCardOptions(), measure).size)
                    for (page in overflow) assertTrue(layoutQuoteCard(page, QuoteCardOptions(format = CardFormat.SQUARE), measure).fits)
                    File(directory,"journal.html").writeText(readingJournalHtml(source.title,source.author,listOf(source,long),JournalOptions(ruledSpace = true)))
                } finally { scene.close() }
            } catch (e: Throwable) { failure = e }
        }
        failure?.let { throw it }
    }
}
