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
 * Regression for the "Contents shows nothing but the header" bug seen on some EPUB2 books
 * (e.g. Robin Hobb's Assassin's Quest): the machine NCX is stubbed down to a single
 * "Start" navPoint aimed at the cover, so the Contents projected exactly one useless row —
 * yet the book still ships a full human-readable HTML Contents page, linked from the OPF
 * <guide type="toc">. parseBookToc must fall back to that guide page when the nav is degenerate,
 * and must NOT touch books whose nav is healthy.
 */
class GuideHtmlTocFallbackTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() { tempRoot = createTempDir("folio-guidetoc-") }

    @AfterTest
    fun tearDown() { tempRoot.deleteRecursively() }

    private val prose = ("Word ".repeat(200)).trim()

    private fun prosePage(heading: String) =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml"><head><title>$heading</title></head>
           <body><section><h1>$heading</h1><p>$prose</p></section></body></html>""".trimIndent()

    private val coverPage =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Cover</title></head>
           <body><figure><img src="../Images/cover.jpg"/></figure></body></html>""".trimIndent()

    // The publisher's HTML Contents page. Hrefs are relative to this page (which lives in Text/),
    // exactly like the real Hobb EPUB: "../Text/…". One entry carries nested markup to prove the
    // anchor-text walk handles it.
    private val htmlToc =
        """<?xml version="1.0" encoding="utf-8"?>
           <html xmlns="http://www.w3.org/1999/xhtml"><head><title>Table of Contents</title></head>
           <body><div id="toc">
             <p class="fmh">Contents</p>
             <p class="ccn"><a href="../Text/chapter1.htm">Chapter One</a></p>
             <p class="ccn"><a href="../Text/chapter2.htm">Chapter Two</a></p>
             <p class="ccn"><a href="../Text/chapter3.htm">Chapter Three</a></p>
             <p class="ccn"><a href="../Text/preview.htm">Preview of <em>Book Two</em></a></p>
           </div></body></html>""".trimIndent()

    private fun opf(guide: String, ncxNavPoints: String) =
        """<?xml version="1.0" encoding="UTF-8"?>
           <package xmlns="http://www.idpf.org/2007/opf" version="2.0" unique-identifier="uid">
             <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
               <dc:title>Stub NCX Book</dc:title><dc:creator>Tester</dc:creator>
               <dc:identifier id="uid">stub-ncx</dc:identifier>
             </metadata>
             <manifest>
               <item id="ncx" href="toc.ncx" media-type="application/x-dtbncx+xml"/>
               <item id="cover" href="Text/cover.htm" media-type="application/xhtml+xml"/>
               <item id="toc" href="Text/toc.htm" media-type="application/xhtml+xml"/>
               <item id="ch1" href="Text/chapter1.htm" media-type="application/xhtml+xml"/>
               <item id="ch2" href="Text/chapter2.htm" media-type="application/xhtml+xml"/>
               <item id="ch3" href="Text/chapter3.htm" media-type="application/xhtml+xml"/>
               <item id="prev" href="Text/preview.htm" media-type="application/xhtml+xml"/>
             </manifest>
             <spine toc="ncx">
               <itemref idref="cover"/>
               <itemref idref="toc"/>
               <itemref idref="ch1"/>
               <itemref idref="ch2"/>
               <itemref idref="ch3"/>
               <itemref idref="prev"/>
             </spine>
             $guide
           </package>""".trimIndent()

    // Degenerate: one navPoint, aimed at the cover — the exact shape that produced a one-row Contents.
    private val stubNcx =
        """<?xml version="1.0" encoding="UTF-8"?>
           <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
             <navPoint id="n1"><navLabel><text>Start</text></navLabel><content src="Text/cover.htm"/></navPoint>
           </navMap></ncx>""".trimIndent()

    private val healthyNcx =
        """<?xml version="1.0" encoding="UTF-8"?>
           <ncx xmlns="http://www.daisy.org/z3986/2005/ncx/" version="2005-1"><navMap>
             <navPoint id="n1"><navLabel><text>Chapter One</text></navLabel><content src="Text/chapter1.htm"/></navPoint>
             <navPoint id="n2"><navLabel><text>Chapter Two</text></navLabel><content src="Text/chapter2.htm"/></navPoint>
             <navPoint id="n3"><navLabel><text>Chapter Three</text></navLabel><content src="Text/chapter3.htm"/></navPoint>
           </navMap></ncx>""".trimIndent()

    private val container =
        """<?xml version="1.0"?>
           <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
             <rootfiles><rootfile full-path="OEBPS/content.opf" media-type="application/oebps-package+xml"/></rootfiles>
           </container>""".trimIndent()

    private fun writeEpub(guide: String, ncx: String): File {
        val file = File(tempRoot, "guide-toc.epub")
        ZipOutputStream(file.outputStream()).use { zip ->
            fun entry(name: String, body: String) {
                zip.putNextEntry(ZipEntry(name)); zip.write(body.toByteArray()); zip.closeEntry()
            }
            entry("META-INF/container.xml", container)
            entry("OEBPS/content.opf", opf(guide, ncx))
            entry("OEBPS/toc.ncx", ncx)
            entry("OEBPS/Text/cover.htm", coverPage)
            entry("OEBPS/Text/toc.htm", htmlToc)
            entry("OEBPS/Text/chapter1.htm", prosePage("Chapter One"))
            entry("OEBPS/Text/chapter2.htm", prosePage("Chapter Two"))
            entry("OEBPS/Text/chapter3.htm", prosePage("Chapter Three"))
            entry("OEBPS/Text/preview.htm", prosePage("Preview"))
        }
        return file
    }

    private val guideToc =
        """<guide><reference type="toc" title="Table of Contents" href="Text/toc.htm"/></guide>"""

    @Test
    fun `stub NCX falls back to the guide HTML contents page`() = runBlocking {
        val parser = EpubParser()
        val epub = writeEpub(guide = guideToc, ncx = stubNcx).absolutePath
        val chapters = parser.parseEpub(epub).chapters
        val titles = parser.parseBookToc(epub, chapters).map { it.title }

        assertFalse("Start" in titles, "the stub NCX 'Start' row must not be the whole Contents: $titles")
        assertTrue("Chapter One" in titles, "guide HTML contents must be recovered: $titles")
        assertEquals(
            listOf("Chapter One", "Chapter Two", "Chapter Three", "Preview of Book Two"),
            titles,
            "guide HTML anchors (incl. nested markup) become the Contents in order"
        )
    }

    @Test
    fun `healthy NCX is used verbatim and the guide fallback never fires`() = runBlocking {
        val parser = EpubParser()
        // Guide points at the same HTML page, but the NCX is already good, so it must win untouched.
        val epub = writeEpub(guide = guideToc, ncx = healthyNcx).absolutePath
        val chapters = parser.parseEpub(epub).chapters
        val titles = parser.parseBookToc(epub, chapters).map { it.title }

        // NCX has no "Preview" entry; if the guide had leaked in, "Preview of Book Two" would appear.
        assertEquals(listOf("Chapter One", "Chapter Two", "Chapter Three"), titles)
    }

    @Test
    fun `no guide and a stub NCX leaves the caller to fall back (empty or single)`() = runBlocking {
        val parser = EpubParser()
        val epub = writeEpub(guide = "", ncx = stubNcx).absolutePath
        val chapters = parser.parseEpub(epub).chapters
        val rows = parser.parseBookToc(epub, chapters)
        // Nothing to recover from: behaviour is unchanged (at most the single stub row).
        assertTrue(rows.size <= 1, "without a guide the degenerate nav is left as-is: ${rows.map { it.title }}")
    }
}
