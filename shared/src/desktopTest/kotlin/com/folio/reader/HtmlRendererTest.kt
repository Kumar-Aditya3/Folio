package com.folio.reader

import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.sp
import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.render.HtmlRenderer
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Verifies the EPUB HTML -> AnnotatedString pipeline on desktop JVM.
 *
 * Covers (logic level):
 *  - P3-01  chapter content extraction and rendering
 */
class HtmlRendererTest {

    private val rendererSettings = ReaderSettings()
    private val renderer by lazy { HtmlRenderer(rendererSettings) }

    private fun isSkikoAvailable(): Boolean = try {
        // Trigger skiko native load; if missing, tests should be skipped not failed.
        renderer
        true
    } catch (e: Throwable) {
        if (e.message?.contains("skiko") == true || e.cause?.message?.contains("skiko") == true) {
            println("SKIP HtmlRendererTest: skiko native missing (${e.message})")
            false
        } else throw e
    }

    private fun render(html: String) = try {
        renderer.renderToAnnotatedString(html, TextStyle(fontSize = 16.sp))
    } catch (e: Throwable) {
        if (e.message?.contains("skiko") == true || e.cause?.message?.contains("skiko") == true) {
            println("SKIP render due to skiko: ${e.message}")
            // Return empty placeholder so assertions can be skipped at call site
            androidx.compose.ui.text.AnnotatedString("")
        } else throw e
    }

    @Test
    fun `renders headings paragraphs emphasis and lists`() {
        val html = """
            <html><body>
              <h1>Chapter One</h1>
              <p>Hello <em>emphasised</em> and <strong>bold</strong> world.</p>
              <ul><li>first</li><li>second</li></ul>
              <blockquote>A quoted line.</blockquote>
              <p>Link to <a href="ch2.xhtml">next chapter</a>.</p>
            </body></html>
        """.trimIndent()

        val result = render(html)
        val text = result.text

        assertTrue(text.contains("Chapter One"), "heading text must survive, got: $text")
        assertTrue(text.contains("Hello emphasised and bold world."), "paragraph text must be joined: $text")
        assertTrue(text.contains("first") && text.contains("second"), "list items must render")
        assertTrue(text.contains("A quoted line."), "blockquote text must render")
        assertTrue(result.spanStyles.isNotEmpty(), "em/strong/a must produce span styles")
    }

    @Test
    fun `script and style contents are not rendered`() {
        val html = """
            <html><head><style>p.hidden-marker { color: red }</style>
            <script>var secretMarker = 42;</script></head>
            <body><p>Visible text.</p></body></html>
        """.trimIndent()

        val text = render(html).text
        assertTrue(text.contains("Visible text."))
        assertTrue(!text.contains("secretMarker"), "script body must be skipped: $text")
        assertTrue(!text.contains("hidden-marker"), "style body must be skipped: $text")
    }

    @Test
    fun `empty and malformed input do not crash`() {
        assertTrue(render("").text.isEmpty())
        assertTrue(render("<p>unclosed paragraph").text.contains("unclosed"))
        assertTrue(render("plain text no tags").text.contains("plain text no tags"))
        assertTrue(render("</p></div>").text.isEmpty())
    }

    @Test
    fun `drop cap spans stay on one line`() {
        // Real-world pattern: <span class="dropcap">P</span>ROLOGUE — inline spans
        // must NOT force line breaks (ParagraphStyle is per block, not per run).
        val html = "<p><span class=\"dropcap\">P</span>ROLOGUE</p><p>T<span>he</span> text continues.</p>"
        val text = render(html).text
        assertTrue(text.contains("PROLOGUE"), "drop-cap word must be contiguous, got: $text")
        assertTrue(text.contains("The text continues."), "inline span must join, got: $text")
    }

    @Test
    fun `publisher center alignment is preserved for dedications`() {
        val html = "<p style=\"text-align:center\">THIS ONE IS FOR</p>"
        val result = render(html)
        assertTrue(result.paragraphStyles.isNotEmpty(), "block paragraph style must exist")
        assertEquals(TextAlign.Center, result.paragraphStyles.single().item.textAlign)
        assertTrue(result.text.contains("THIS ONE IS FOR"))
    }

    @Test
    fun `embedded epub stylesheet styles class and heading selectors`() {
        val result = render(
            """
            <html><head><style>
              h1 { font-size: 30px; text-align: center; }
              .epigraph { font-style: italic; letter-spacing: 1px; }
            </style></head><body>
              <h1>Chapter</h1><p><span class="epigraph">A remembered line</span></p>
            </body></html>
            """.trimIndent()
        )
        val heading = result.text.indexOf("Chapter")
        val epigraph = result.text.indexOf("A remembered")
        assertEquals(TextAlign.Center, result.paragraphStyles.first {
            it.start <= heading && it.end >= heading
        }.item.textAlign)
        assertTrue(result.spanStyles.any {
            it.start <= epigraph && it.end >= epigraph &&
                it.item.fontStyle == androidx.compose.ui.text.font.FontStyle.Italic
        })
    }

    @Test
    fun `nested headings retain their typography and publisher alignment`() {
        val result = render(
            "<div style=\"text-align: center\"><h1>Book title</h1><p>By Author</p></div>"
        )
        val headingStart = result.text.indexOf("Book title")
        val bylineStart = result.text.indexOf("By Author")

        assertTrue(headingStart >= 0 && bylineStart >= 0, "nested content must render: ${result.text}")
        assertEquals(
            TextAlign.Center,
            result.paragraphStyles.first { it.start <= headingStart && it.end >= headingStart }.item.textAlign
        )
        assertEquals(
            TextAlign.Center,
            result.paragraphStyles.first { it.start <= bylineStart && it.end >= bylineStart }.item.textAlign
        )
        assertTrue(
            result.spanStyles.any {
                it.start <= headingStart && it.end >= headingStart && it.item.fontSize == (rendererSettings.fontSize * 2.0f).sp
            },
            "nested h1 must retain its larger publisher heading style"
        )
    }

    @Test
    fun `normalized formatting ignores publisher alignment`() {
        val normalized = HtmlRenderer(
            rendererSettings.copy(formattingMode = FormattingMode.NORMALIZED)
        ).renderToAnnotatedString(
            "<p style=\"text-align:center\">Body text</p>",
            TextStyle(fontSize = 16.sp)
        )

        assertEquals(TextAlign.Start, normalized.paragraphStyles.single().item.textAlign)
    }

    @Test
    fun `default paragraph spacing does not create blank lines`() {
        val text = render("<p>first</p><p>second</p>").text
        assertTrue(!text.contains("\n\n"), "default spacing must not create blank lines: ${text.replace("\n", "⏎")}")
        assertTrue(text.contains("first") && text.contains("second"))
    }

    @Test
    fun `reader justification is applied to paragraph style`() {
        val justified = HtmlRenderer(
            rendererSettings.copy(alignment = com.folio.reader.settings.TextAlignment.JUSTIFIED)
        ).renderToAnnotatedString("<p>Body text</p>", TextStyle(fontSize = 16.sp))

        assertEquals(TextAlign.Justify, justified.paragraphStyles.single().item.textAlign)
    }

    @Test
    fun `reader justification overrides ordinary publisher left alignment`() {
        val justified = HtmlRenderer(
            rendererSettings.copy(alignment = com.folio.reader.settings.TextAlignment.JUSTIFIED)
        ).renderToAnnotatedString(
            "<p style=\"text-align:left\">Body text</p>",
            TextStyle(fontSize = 16.sp)
        )

        assertEquals(TextAlign.Justify, justified.paragraphStyles.single().item.textAlign)
    }

    @Test
    fun `css hidden structural content is omitted and text indent is measured`() {
        val result = render(
            """
            <style>
              .metadata { display: none; }
              p.body { text-indent: 24px; }
            </style>
            <div class="metadata">Book title repeated in metadata</div>
            <p class="body">A real paragraph.</p>
            """.trimIndent()
        )
        assertTrue(!result.text.contains("repeated in metadata"))
        assertTrue(result.paragraphStyles.any { it.item.textIndent?.firstLine?.value == 24f })
    }

    @Test
    fun `whitespace only runs do not create empty paragraphs`() {
        val html = "<p>first</p>\n   <p>second</p><p>&nbsp;</p><p>third</p>"
        val text = render(html).text
        // No more than one blank line between blocks; &nbsp;-only paragraph skipped
        assertTrue(!text.contains("\n\n\n"), "no stacked blank lines, got: ${text.replace("\n", "⏎")}")
        assertTrue(text.contains("first") && text.contains("second") && text.contains("third"))
    }

    @Test
    fun `nested blocks do not overlap paragraph styles`() {
        // div>p nesting used to push two overlapping ParagraphStyles and crash
        val html = """
            <div class="chapter"><section><p>First para.</p><p>Second para.</p></section></div>
        """.trimIndent()
        val text = render(html).text
        assertTrue(text.contains("First para.") && text.contains("Second para."), "got: $text")
    }

    @Test
    fun `image inside paragraph splits blocks without crashing`() {
        val html = "<div><p>before <img src=\"../images/pic.jpg\"/> after</p><p>next</p></div>"
        val blocks = renderer.renderToBlocks(html, TextStyle(fontSize = 16.sp))
        assertTrue(blocks.any { it is com.folio.reader.ui.render.HtmlBlock.Image }, "image block expected")
        val text = blocks.filterIsInstance<com.folio.reader.ui.render.HtmlBlock.Text>().joinToString { it.annotated.text }
        assertTrue(text.contains("before") && text.contains("after") && text.contains("next"), "got: $text")
    }

    @Test
    fun `epub resource references are retained for platform surface`() {
        val blocks = renderer.renderToBlocks(
            "<p>Before</p><img src=\"../images/illustration.webp\"/><p>After</p>",
            TextStyle(fontSize = 16.sp)
        )
        assertEquals("../images/illustration.webp", (blocks.filterIsInstance<com.folio.reader.ui.render.HtmlBlock.Image>().single()).src)
    }

    @Test
    fun `reader typography changes reflow rendered chapter`() {
        val small = HtmlRenderer(rendererSettings.copy(fontSize = 14f))
            .renderToBlocks("<p>Reflow this chapter</p>", TextStyle(fontSize = 14.sp))
        val large = HtmlRenderer(rendererSettings.copy(fontSize = 24f))
            .renderToBlocks("<p>Reflow this chapter</p>", TextStyle(fontSize = 24.sp))
        val smallSize = small.filterIsInstance<com.folio.reader.ui.render.HtmlBlock.Text>().single().annotated.spanStyles
            .first().item.fontSize
        val largeSize = large.filterIsInstance<com.folio.reader.ui.render.HtmlBlock.Text>().single().annotated.spanStyles
            .first().item.fontSize
        assertTrue(largeSize > smallSize, "reader settings must affect rendered typography")
    }
}
