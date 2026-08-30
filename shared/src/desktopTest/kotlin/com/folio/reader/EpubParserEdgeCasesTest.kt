package com.folio.reader

import com.folio.reader.epub.EpubParser
import kotlinx.coroutines.runBlocking
import kotlinx.datetime.toLocalDateTime
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Real-world EPUB2 compatibility (audit F18): declared non-UTF-8 charsets,
 * percent-encoded hrefs, and partial dc:date values must all parse.
 */
class EpubParserEdgeCasesTest {

    private lateinit var tempRoot: File

    @BeforeTest
    fun setUp() {
        tempRoot = createTempDir("folio-epub-edge-")
    }

    @AfterTest
    fun tearDown() {
        tempRoot.deleteRecursively()
    }

    private fun containerXml(opfPath: String) = """
        <?xml version="1.0"?>
        <container version="1.0" xmlns="urn:oasis:names:tc:opendocument:xmlns:container">
          <rootfiles>
            <rootfile full-path="$opfPath" media-type="application/oebps-package+xml"/>
          </rootfiles>
        </container>
    """.trimIndent()

    private fun opf(dateValue: String, chapterHref: String) = """
        <?xml version="1.0" encoding="UTF-8"?>
        <package xmlns="http://www.idpf.org/2007/opf" version="2.0">
          <metadata xmlns:dc="http://purl.org/dc/elements/1.1/">
            <dc:title>Edge Case Book</dc:title>
            <dc:creator>Tester</dc:creator>
            <dc:date>$dateValue</dc:date>
          </metadata>
          <manifest>
            <item id="ch1" href="$chapterHref" media-type="application/xhtml+xml"/>
          </manifest>
          <spine>
            <itemref idref="ch1"/>
          </spine>
        </package>
    """.trimIndent()

    private fun writeEpub(name: String, chapterEntryName: String, chapterBytes: ByteArray, manifestHref: String, dateValue: String): File {
        val file = File(tempRoot, name)
        ZipOutputStream(file.outputStream()).use { zip ->
            zip.putNextEntry(ZipEntry("META-INF/container.xml"))
            zip.write(containerXml("OEBPS/content.opf").toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry("OEBPS/content.opf"))
            zip.write(opf(dateValue, manifestHref).toByteArray())
            zip.closeEntry()
            zip.putNextEntry(ZipEntry(chapterEntryName))
            zip.write(chapterBytes)
            zip.closeEntry()
        }
        return file
    }

    @Test
    fun `percent-encoded chapter hrefs resolve to decoded zip entries`() = runBlocking {
        val chapter = "<?xml version=\"1.0\" encoding=\"UTF-8\"?><html><body><p>Spaced chapter text.</p></body></html>"
        // Manifest carries the percent-encoded form; the zip entry is decoded.
        val epub = writeEpub(
            name = "spaced.epub",
            chapterEntryName = "OEBPS/Chapter One.xhtml",
            chapterBytes = chapter.toByteArray(),
            manifestHref = "Chapter%20One.xhtml",
            dateValue = "2011"
        )

        val parsed = EpubParser().parseEpub(epub.absolutePath)
        assertEquals(1, parsed.chapters.size, "percent-encoded href must resolve to the chapter")
        assertTrue(parsed.rawHtmlByHref.values.any { it.contains("Spaced chapter text.") })
    }

    @Test
    fun `chapters with a declared ISO-8859-1 charset decode correctly`() = runBlocking {
        // "café" with a literal ISO-8859-1 é (0xE9) — invalid as UTF-8.
        val out = java.io.ByteArrayOutputStream()
        out.write("<?xml version=\"1.0\" encoding=\"ISO-8859-1\"?><html><body><p>caf".toByteArray(Charsets.ISO_8859_1))
        out.write(0xE9)
        out.write("</p></body></html>".toByteArray(Charsets.ISO_8859_1))
        val chapterBytes = out.toByteArray()

        val epub = writeEpub(
            name = "latin1.epub",
            chapterEntryName = "OEBPS/ch1.xhtml",
            chapterBytes = chapterBytes,
            manifestHref = "ch1.xhtml",
            dateValue = "2011"
        )

        val parsed = EpubParser().parseEpub(epub.absolutePath)
        val html = parsed.rawHtmlByHref.values.single()
        assertTrue(html.contains("café"), "declared charset must be honored, got: $html")
    }

    @Test
    fun `partial dc date values parse to instants`() = runBlocking {
        suspend fun publicationYear(dateValue: String): Int? {
            val epub = writeEpub(
                name = "date-${dateValue.replace(Regex("[^0-9]"), "")}.epub",
                chapterEntryName = "OEBPS/ch1.xhtml",
                chapterBytes = "<html><body><p>x</p></body></html>".toByteArray(),
                manifestHref = "ch1.xhtml",
                dateValue = dateValue
            )
            return EpubParser().parseEpub(epub.absolutePath).metadata.publicationDate
                ?.toLocalDateTime(kotlinx.datetime.TimeZone.UTC)?.year
        }

        assertEquals(2011, publicationYear("2011"))
        assertEquals(2011, publicationYear("2011-05"))
        assertEquals(2011, publicationYear("2011-05-01"))
    }
}
