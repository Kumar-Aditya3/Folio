package com.folio.reader

import com.folio.reader.experience.*
import com.folio.reader.ui.render.exportJournalPdf
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.Loader
import org.apache.pdfbox.text.PDFTextStripper
import org.apache.pdfbox.rendering.PDFRenderer
import org.junit.jupiter.api.Assumptions.assumeTrue
import java.io.File
import java.util.Base64
import javax.imageio.ImageIO
import kotlin.test.*

class ReadingJournalPdfTest {
    @Test fun windowsBrowserPrintsSelectableTextAndCorrectPaperSizes() = runBlocking {
        assumeTrue(System.getProperty("os.name").startsWith("Windows"))
        val cache = File(System.getenv("LOCALAPPDATA") ?: System.getProperty("user.home"), "Folio/jcef")
        assumeTrue(cache.isDirectory, "Uses an installed JCEF runtime; never downloads one for a test")
        val root = File("build/visual-review/beautiful-exports").apply { mkdirs() }
        val css = listOf("FolioDisplay" to "fraunces_variable.ttf", "FolioText" to "manrope_variable.ttf").joinToString("\n") { (family, file) ->
            val bytes = javaClass.classLoader.getResourceAsStream("fonts/$file")!!.use { it.readBytes() }
            "@font-face{font-family:$family;src:url(data:font/ttf;base64,${Base64.getEncoder().encodeToString(bytes)})}"
        }
        val source = ExportSource("h", "b", "A quiet afternoon", "A Folio reader", "A thought worth keeping.\n\n" + "Reading creates a room for attention. ".repeat(350), "Chapter one", note = "A note in my own words.")
        for (letter in listOf(false,true)) {
            val file = File(root, if (letter) "journal-letter.pdf" else "journal-a4.pdf")
            val html = readingJournalHtml(source.title,source.author,listOf(source),JournalOptions(letter = letter, ruledSpace = true),fontCss = css)
            exportJournalPdf(html,file)
            Loader.loadPDF(file).use { pdf ->
                assertTrue(pdf.numberOfPages >= 3)
                val text = PDFTextStripper().getText(pdf)
                assertTrue(text.contains("A thought worth keeping."))
                assertTrue(text.contains("A note in my own words."))
                assertEquals(if (letter) 612f else 595.28f,pdf.getPage(0).mediaBox.width, 2f)
                val renderer = PDFRenderer(pdf)
                for (page in 0 until minOf(3, pdf.numberOfPages)) ImageIO.write(renderer.renderImageWithDPI(page, 90f), "png", File(root,"journal-${if (letter) "letter" else "a4"}-$page.png"))
            }
        }
    }
}
