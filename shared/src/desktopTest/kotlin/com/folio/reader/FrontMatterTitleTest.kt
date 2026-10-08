package com.folio.reader

import com.folio.reader.epub.EpubParser
import kotlinx.coroutines.runBlocking
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Front matter (credits, acknowledgements, dedication) names itself with styled paragraphs
 * rather than h1..h6. It used to fall through to a label built from the spine position, so the
 * Contents list printed "Chapter 10" between "Acknowledgments" and "Dramatis Personae".
 */
class FrontMatterTitleTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-epub-frontmatter-")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun chapterTitles(bookTitle: String, vararg documents: Pair<String, String>): List<String> {
        val ids = documents.indices.map { "doc$it" }
        // Joined with the raw string's own indent: trimIndent measures the common indent
        // *after* interpolation, so column-0 continuation lines would defeat it and leave
        // leading spaces before the <?xml prolog.
        val items = documents.mapIndexed { i, (href, _) ->
            """<item id="${ids[i]}" href="$href" media-type="application/xhtml+xml"/>"""
        }.joinToString("\n            ")
        val refs = ids.joinToString("\n            ") { """<itemref idref="$it"/>""" }
        val opf = """
            <?xml version="1.0" encoding="UTF-8"?>
            <package xmlns="http://www.idpf.org/2007/opf" version="3.0">
              <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
                <dc:title>$bookTitle</dc:title>
              </metadata>
              <manifest>
            $items
              </manifest>
              <spine>
            $refs
              </spine>
            </package>
        """.trimIndent()

        val file = File(tempRoot, "matter.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(
                """
                <?xml version="1.0"?>
                <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
                  <rootfiles>
                    <rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/>
                  </rootfiles>
                </container>
                """.trimIndent().toByteArray()
            )
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf.toByteArray())
            zip.closeEntry()
            for ((href, html) in documents) {
                zip.putNextEntry(ZipEntry("OEBPS/$href"))
                zip.write(html.toByteArray())
                zip.closeEntry()
            }
        }
        return runBlocking { EpubParser().parseEpub(file.absolutePath).chapters.map { it.title } }
    }

    private val prose = "The tide was just turning now and the waves fell short. " +
        "She kept her eyes on the straggling line of seaweed and shells. ".repeat(6)

    @Test
    fun `acknowledgements page with no heading is named from its own text`() = runBlocking {
        val page = """
            <html><head><title>Ship of Magic</title></head><body>
              <div id="ack"><p class="fmh"><small><strong>ACKNOWLEDGMENTS</strong></small></p>
              <p class="fmtx">The author would like to thank Gale Zimmerman.</p></div>
            </body></html>
        """.trimIndent()
        val titles = chapterTitles("Ship of Magic", "section_004.htm" to page)
        assertEquals(listOf("Acknowledgments"), titles)
    }

    @Test
    fun `copyright page is named from the publisher filename token`() = runBlocking {
        val page = """
            <html><head><title>Ship of Magic</title></head><body>
              <div id="cop"><p class="cit"><small><strong>SHIP OF MAGIC</strong></small></p>
              <p class="cit">Bantam hardcover edition published 1998</p></div>
            </body></html>
        """.trimIndent()
        val titles = chapterTitles("Ship of Magic", "Hobb_9780553900255_epub_cop_r1.htm" to page)
        assertEquals(listOf("Copyright"), titles)
    }

    @Test
    fun `chapter heading rendered as a paragraph is not renumbered from the spine`() = runBlocking {
        val page = """
            <html><head><title>Ship of Magic</title></head><body>
              <div class="bodymatter"><p class="cmtitle">CHAPTER ONE</p>
              <p class="cmtitle">OF PRIESTS AND PIRATES</p><p>$prose</p></div>
            </body></html>
        """.trimIndent()
        val titles = chapterTitles("Ship of Magic", "Hobb_9780553900255_epub_c01_r1.htm" to page)
        assertEquals(listOf("CHAPTER ONE"), titles)
    }

    @Test
    fun `short furniture pages are not given a chapter number`() = runBlocking {
        val page = """
            <html><head><title>Ship of Magic</title></head><body>
              <div id="fm1"><img src="art.jpg" alt=""/></div>
            </body></html>
        """.trimIndent()
        val titles = chapterTitles("Ship of Magic", "Hobb_9780553900255_epub_fm1_r1.htm" to page)
        assertEquals(listOf("Untitled"), titles)
    }

    @Test
    fun `prose pages that name themselves nowhere still get a chapter number`() = runBlocking {
        val page = """
            <html><head><title>Ship of Magic</title></head><body>
              <div class="bodymatter"><p>$prose</p><p>$prose</p></div>
            </body></html>
        """.trimIndent()
        val titles = chapterTitles("Ship of Magic", "chapter_body.xhtml" to page)
        assertEquals(1, titles.size)
        assertTrue(titles[0].matches(Regex("Chapter \\d+")), "expected a number, got: ${titles[0]}")
    }

    @Test
    fun `real spine order keeps every page distinct and front matter ahead of the body`() = runBlocking {
        val titles = chapterTitles(
            "Ship of Magic",
            "titlepage.xhtml" to "<html><head><title>Ship of Magic</title></head><body><p>x</p></body></html>",
            "Hobb_epub_ack_r1.htm" to """
                <html><head><title>Ship of Magic</title></head><body>
                <p class="fmh"><strong>ACKNOWLEDGMENTS</strong></p></body></html>
            """.trimIndent(),
            "Hobb_epub_c01_r1.htm" to """
                <html><head><title>Ship of Magic</title></head><body>
                <p class="cmtitle">CHAPTER ONE</p><p>$prose</p></body></html>
            """.trimIndent()
        )
        assertEquals(listOf("Title Page", "Acknowledgments", "CHAPTER ONE"), titles)
    }
}
