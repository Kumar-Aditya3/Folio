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
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/**
 * Regression for the "in-book illustration shows up in Contents named with a page number" bug.
 *
 * An EPUB3 nav document carries several <nav> lists. The reading-order `toc` is the Contents;
 * `page-list` maps every print page (labelled with its bare page number) and `landmarks` points
 * at cover/toc/bodymatter. A full-page illustration that lives on its own spine file is only ever
 * referenced by the `page-list`, so folding every nav <a> into the Contents named that spine file
 * after its page number ("20"). Only the `toc` nav may reach Contents.
 *
 * Mirrors the real structure of the Alya Vol.1 EPUB used to find this.
 */
class NavTocPageListTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() { tempRoot = createTempDir("folio-navtoc-") }

    @AfterTest
    fun tearDown() { tempRoot.deleteRecursively() }

    private val prose = ("Word ".repeat(200)).trim()

    private fun prosePage(heading: String) =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$heading</title></head>
           <body><section><h1>$heading</h1><p>$prose</p></section></body></html>""".trimIndent()

    // An image-only insert: no heading, only a page-break marker + <img>. This is the leaf that
    // used to be named after its print page number.
    private val insertPage =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
           <head><title>Chapter 1</title></head>
           <body><figure><span epub:type="pagebreak" id="pg_20"/><img src="../images/ill_001.jpg"/></figure></body>
           </html>""".trimIndent()

    private val nav =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml" xmlns:epub="http://www.idpf.org/2007/ops">
           <head><title>TOC</title></head><body>
           <nav epub:type="toc" role="doc-toc"><ol>
             <li><a href="Text/cover.xhtml">Cover</a></li>
             <li><a href="Text/prologue.xhtml">Prologue</a></li>
             <li><a href="Text/chapter001.xhtml">Chapter 1</a></li>
             <li><a href="Text/chapter002.xhtml">Chapter 2</a></li>
           </ol></nav>
           <nav epub:type="landmarks" role="doc-landmarks"><ol>
             <li><a epub:type="cover" href="Text/cover.xhtml">Cover</a></li>
             <li><a epub:type="bodymatter" href="Text/chapter001.xhtml">Start</a></li>
           </ol></nav>
           <nav epub:type="page-list" role="doc-pagelist"><ol>
             <li><a href="Text/chapter001.xhtml#pg_9">9</a></li>
             <li><a href="Text/chapter001-01.xhtml#pg_20">20</a></li>
             <li><a href="Text/chapter002.xhtml#pg_21">21</a></li>
           </ol></nav>
           </body></html>""".trimIndent()

    private val opf =
        """<?xml version="1.0" encoding="UTF-8"?>
           <package xmlns="http://www.idpf.org/2007/opf" version="3.0" unique-identifier="uid">
             <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
               <dc:title>Nav Probe</dc:title><dc:creator>Tester</dc:creator>
               <dc:identifier id="uid">nav-probe</dc:identifier>
             </metadata>
             <manifest>
               <item id="nav" href="nav.xhtml" media-type="application/xhtml+xml" properties="nav"/>
               <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
               <item id="cover" href="Text/cover.xhtml" media-type="application/xhtml+xml"/>
               <item id="prologue" href="Text/prologue.xhtml" media-type="application/xhtml+xml"/>
               <item id="ch1" href="Text/chapter001.xhtml" media-type="application/xhtml+xml"/>
               <item id="ch1ins" href="Text/chapter001-01.xhtml" media-type="application/xhtml+xml"/>
               <item id="ch2" href="Text/chapter002.xhtml" media-type="application/xhtml+xml"/>
             </manifest>
             <spine toc="ncx">
               <itemref idref="cover"/>
               <itemref idref="prologue"/>
               <itemref idref="ch1"/>
               <itemref idref="ch1ins"/>
               <itemref idref="ch2"/>
             </spine>
           </package>""".trimIndent()

    // A minimal NCX with only the reading-order navMap (no page targets), present so the book has
    // an EPUB2 fallback too; parseToc must still prefer the EPUB3 nav.
    private val ncx =
        """<?xml version="1.0" encoding="UTF-8"?>
           <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
             <navPoint id="n1"><navLabel><text>Cover</text></navLabel><content src="Text/cover.xhtml"/></navPoint>
             <navPoint id="n2"><navLabel><text>Prologue</text></navLabel><content src="Text/prologue.xhtml"/></navPoint>
             <navPoint id="n3"><navLabel><text>Chapter 1</text></navLabel><content src="Text/chapter001.xhtml"/></navPoint>
             <navPoint id="n4"><navLabel><text>Chapter 2</text></navLabel><content src="Text/chapter002.xhtml"/></navPoint>
           </navMap></ncx>""".trimIndent()

    private val container =
        """<?xml version="1.0"?>
           <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
             <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
           </container>""".trimIndent()

    private fun writeEpub(): File {
        val file = File(tempRoot, "nav-probe.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", container)
            entry("OEBPS/content.opf", opf)
            entry("OEBPS/nav.xhtml", nav)
            entry("OEBPS/toc.ncx", ncx)
            entry("OEBPS/Text/cover.xhtml", prosePage("Cover"))
            entry("OEBPS/Text/prologue.xhtml", prosePage("Prologue"))
            entry("OEBPS/Text/chapter001.xhtml", prosePage("Chapter 1"))
            entry("OEBPS/Text/chapter001-01.xhtml", insertPage)
            entry("OEBPS/Text/chapter002.xhtml", prosePage("Chapter 2"))
        }
        return file
    }

    @Test
    fun `nav parsing ignores page-list and landmarks`() = runBlocking {
        val parser = EpubParser()
        val parsed = parser.parseEpub(writeEpub().absolutePath)
        val labels = parsed.toc.map { it.label }
        assertTrue("Chapter 1" in labels, "toc nav must be read: $labels")
        assertFalse(labels.any { it.matches(Regex("\\d+")) }, "page-list numbers must not enter the toc: $labels")
        assertFalse("Start" in labels, "landmarks entries must not enter the toc: $labels")
        assertEquals(listOf("Cover", "Prologue", "Chapter 1", "Chapter 2"), labels)
    }

    @Test
    fun `book toc rows are the clean nav entries, never an illustration page number`() = runBlocking {
        val parser = EpubParser()
        val epub = writeEpub().absolutePath
        val chapters = parser.parseEpub(epub).chapters
        val rows = parser.parseBookToc(epub, chapters)
        val titles = rows.map { it.title }
        assertEquals(listOf("Cover", "Prologue", "Chapter 1", "Chapter 2"), titles)
        assertFalse(titles.any { it.matches(Regex("\\d+")) }, "no Contents row may be a bare page number: $titles")
        // The insert spine file (index 3) must not be pointed at by any row, since the toc nav
        // does not name it.
        assertFalse(rows.any { it.chapterIndex == 3 }, "illustration insert must not become a Contents row")
    }

    @Test
    fun `illustration insert is not numbered as a chapter`() = runBlocking {
        val parser = EpubParser()
        val chapters = parser.parseEpub(writeEpub().absolutePath).chapters
        val insert = chapters[3]
        assertFalse(insert.title.matches(Regex("\\d+")), "insert page named after its page number: '${insert.title}'")
        assertFalse(insert.title.matches(Regex("Chapter \\d+")), "furniture must not be numbered: '${insert.title}'")
    }
}
