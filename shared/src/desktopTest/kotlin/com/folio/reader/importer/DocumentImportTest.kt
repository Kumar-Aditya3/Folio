package com.folio.reader.importer

import com.folio.reader.database.Database
import com.folio.reader.database.JdbcDocumentCategoryRepository
import com.folio.reader.database.JdbcDocumentRepository
import com.folio.reader.database.ThumbnailDocumentRepository
import com.folio.reader.platform.DesktopPlatform
import com.folio.reader.platform.renderDesktopDocumentThumbnail
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdfwriter.compress.CompressParameters
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.jsoup.Jsoup
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentImportTest {
    private lateinit var root: File
    private lateinit var platform: DesktopPlatform
    private lateinit var database: Database
    private lateinit var repository: com.folio.reader.database.DocumentRepository
    private lateinit var categoryRepository: JdbcDocumentCategoryRepository
    private lateinit var importer: DocumentImporter
    private val detector = DocumentFormatDetector()

    @BeforeTest
    fun setUp() {
        root = createTempDir("folio-document-import-")
        platform = DesktopPlatform(root)
        database = Database(platform.fileSystem.getDatabasePath())
        repository = ThumbnailDocumentRepository(
            database,
            JdbcDocumentRepository(database)
        )
        categoryRepository = JdbcDocumentCategoryRepository(database)
        importer = DocumentImporter(
            platform,
            repository,
            categoryRepository,
            ::renderDesktopDocumentThumbnail
        )
    }

    @Test
    fun `docx and odt convert semantic content and reject dangerous archives`() = runBlocking {
        val docx = File(root, "sample.docx")
        zip(docx, mapOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to """<w:document xmlns:w="w"><w:body><w:p><w:pPr><w:pStyle w:val="Heading1"/></w:pPr><w:r><w:t>Heading</w:t></w:r></w:p><w:p><w:pPr><w:numPr/></w:pPr><w:r><w:t>List item</w:t></w:r></w:p><w:tbl><w:tr><w:tc><w:p><w:r><w:t>Cell</w:t></w:r></w:p></w:tc></w:tr></w:tbl></w:body></w:document>"""
        ))
        val docxDocument = importer.importDocument(docx, detector.detect(docx), docx.name)
        val docxHtml = File(platform.fileSystem.getDocumentGeneratedIndexPath(docxDocument.id)).readText()
        assertTrue(docxHtml.contains("<h1>Heading</h1>"))
        assertTrue(docxHtml.contains("<ul><li>List item</li></ul>"))
        assertTrue(docxHtml.contains("<table>"))

        val odt = File(root, "sample.odt")
        zip(odt, mapOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to """<office:document-content xmlns:office="office" xmlns:text="text" xmlns:table="table"><office:body><text:h text:outline-level="2">ODT heading</text:h><text:list><text:list-item><text:p>ODT item</text:p></text:list-item></text:list><table:table><table:table-row><table:table-cell><text:p>ODT cell</text:p></table:table-cell></table:table-row></table:table></office:body></office:document-content>"""
        ))
        val odtDocument = importer.importDocument(odt, detector.detect(odt), odt.name)
        val odtHtml = File(platform.fileSystem.getDocumentGeneratedIndexPath(odtDocument.id)).readText()
        assertTrue(odtHtml.contains("<h2>ODT heading</h2>"))
        assertTrue(odtHtml.contains("<li><p>ODT item</p></li>"))
        assertTrue(odtHtml.contains("<table>"))

        val traversal = File(root, "traversal.docx")
        zip(traversal, mapOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to "<w:document xmlns:w=\"w\"><w:body/></w:document>",
            "../escape.txt" to "bad"
        ))
        assertFailsWith<UnsafeContent> { importer.importDocument(traversal, detector.detect(traversal), traversal.name) }

        val dtd = File(root, "dtd.odt")
        zip(dtd, mapOf(
            "mimetype" to "application/vnd.oasis.opendocument.text",
            "content.xml" to "<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///etc/passwd\">]><x>&e;</x>"
        ))
        assertFailsWith<UnsafeContent> { importer.importDocument(dtd, detector.detect(dtd), dtd.name) }

        val bomb = File(root, "bomb.docx")
        zip(bomb, mapOf(
            "[Content_Types].xml" to "<Types/>",
            "word/document.xml" to "<w:document xmlns:w=\"w\"><w:body>${"A".repeat(200_000)}</w:body></w:document>"
        ))
        assertFailsWith<UnsafeContent> { importer.importDocument(bomb, detector.detect(bomb), bomb.name) }
        Unit
    }

    @Test
    fun `pdf import renders persists and deletes first page thumbnail`() = runBlocking {
        val source = File(root, "thumbnail.pdf")
        PDDocument().use { pdf ->
            pdf.addPage(PDPage())
            pdf.save(source, CompressParameters.NO_COMPRESSION)
        }

        val document = importer.importDocument(source, detector.detect(source), source.name)
        val thumbnail = File(assertNotNull(document.thumbnailPath))
        assertTrue(thumbnail.isFile)
        assertTrue(thumbnail.length() > 0)
        assertEquals(
            listOf(0x89.toByte(), 0x50, 0x4e, 0x47),
            thumbnail.readBytes().take(4)
        )
        assertEquals(document.thumbnailPath, repository.getDocument(document.id)?.thumbnailPath)

        val result = DocumentDeletionService(repository, platform).delete(document.id)
        assertTrue(result.metadataDeleted)
        assertTrue(result.filesDeleted)
        assertFalse(thumbnail.exists())
    }

    @Test
    fun `non pdf import keeps thumbnail fallback path empty`() = runBlocking {
        val source = File(root, "plain.txt").apply { writeText("plain document") }
        val document = importer.importDocument(source, detector.detect(source), source.name)
        assertNull(document.thumbnailPath)
        assertNull(repository.getDocument(document.id)?.thumbnailPath)
    }

    @Test
    fun `failed conversion removes staged and final files`() = runBlocking {
        val malformed = File(root, "broken.pdf").apply { writeText("%PDF-1.7\n/Type /Page") }
        assertFailsWith<CorruptContent> { importer.importDocument(malformed, detector.detect(malformed), malformed.name) }
        val hash = platform.hasher.sha256File(malformed.absolutePath)
        val id = java.util.UUID.nameUUIDFromBytes("folio_document_$hash".toByteArray()).toString()
        assertNull(repository.getDocument(id))
        assertFalse(platform.fileSystem.getDocumentDir(id).exists())
        assertFalse(File(platform.fileSystem.libraryDocumentsDir, ".$id.staging").exists())
    }

    @Test
    fun `deletion removes metadata before files and reports result`() = runBlocking {
        val source = File(root, "delete.txt").apply { writeText("delete me") }
        val document = importer.importDocument(source, detector.detect(source), source.name)
        val result = DocumentDeletionService(repository, platform).delete(document.id)
        assertTrue(result.metadataDeleted)
        assertTrue(result.filesDeleted)
        assertNull(result.cleanupError)
        assertNull(repository.getDocument(document.id))
        assertFalse(platform.fileSystem.getDocumentDir(document.id).exists())
    }

    private fun minimalPdf(title: String? = null, author: String? = null, extra: String = ""): String = buildString {
        append("%PDF-1.7\n1 0 obj << /Type /Catalog >> endobj\n")
        append("2 0 obj << /Type /Pages /Count 1 >> endobj\n")
        append("3 0 obj << /Type /Page /Parent 2 0 R >> endobj\n")
        if (title != null || author != null) append("4 0 obj << ${title?.let { "/Title ($it)" }.orEmpty()} ${author?.let { "/Author ($it)" }.orEmpty()} >> endobj\n")
        if (extra.isNotEmpty()) append("$extra\n")
        append("xref\n0 1\n0000000000 65535 f\ntrailer << /Root 1 0 R >>\nstartxref\n0\n%%EOF")
    }

    private fun zip(file: File, entries: Map<String, String>) {
        ZipOutputStream(file.outputStream()).use { output ->
            entries.forEach { (name, content) ->
                output.putNextEntry(ZipEntry(name))
                output.write(content.toByteArray())
                output.closeEntry()
            }
        }
    }

    @AfterTest
    fun tearDown() {
        runCatching { database.close() }
        root.deleteRecursively()
    }

    @Test
    fun `document storage uses canonical paths staging and recursive cleanup`() = runBlocking {
        val source = File(root, "source.bin").apply { writeText("document bytes") }
        val staged = platform.fileSystem.stageDocumentCopy(source, "doc-1", "PDF")
        assertEquals("original.pdf", File(staged.path).name)
        assertTrue(File(staged.path).isFile)
        val committed = platform.fileSystem.commitStagedDocument("doc-1", "doc-1", "PDF")
        assertEquals(File(root, "library/documents/doc-1/original.pdf").canonicalPath, File(committed).canonicalPath)
        platform.fileSystem.getDocumentGeneratedIndexPath("doc-1").let { File(it).writeText("generated") }
        platform.fileSystem.getDocumentPagesDir("doc-1").resolve("1.png").writeBytes(byteArrayOf(1, 2, 3))
        platform.fileSystem.getDocumentThumbnailsDir("doc-1").resolve("1.png").writeBytes(byteArrayOf(4))
        assertEquals(source.length() + 13, platform.fileSystem.getDocumentSize("doc-1"))
        assertTrue(platform.fileSystem.deleteDocumentFiles("doc-1"))
        assertFalse(platform.fileSystem.getDocumentDir("doc-1").exists())
        assertFailsWith<IllegalArgumentException> { platform.fileSystem.getDocumentDir("../escape") }
        assertFailsWith<IllegalArgumentException> { platform.fileSystem.getDocumentOriginalPath("doc-1", "../pdf") }
    }

    @Test
    fun `detector uses signatures and rejects meaningful conflicts`() {
        val pdf = File(root, "renamed.bin").apply { writeText(minimalPdf()) }
        assertEquals(IncomingFormat.PDF, detector.detect(pdf).format)
        assertFailsWith<UnsafeContent> { detector.detect(pdf, "renamed.txt", "text/plain") }

        val html = File(root, "page.dat").apply { writeText("<!doctype html><html><body>ok</body></html>") }
        assertEquals(IncomingFormat.HTML, detector.detect(html, "page.htm", "text/html").format)

        val text = File(root, "notes.txt").apply { writeText("plain text") }
        assertEquals(IncomingFormat.TXT, detector.detect(text, "notes.txt", "text/plain").format)
        assertFailsWith<UnsafeContent> { detector.detect(text, "notes.txt", "text/html") }
    }

    @Test
    fun `txt import writes generated output and renamed duplicate is detected`() = runBlocking {
        val first = File(root, "first.txt").apply { writeText("<unsafe>\n\nsecond line") }
        val document = importer.importDocument(first, detector.detect(first), first.name)
        val generated = File(platform.fileSystem.getDocumentGeneratedIndexPath(document.id))
        assertTrue(File(document.localPath).isFile)
        assertEquals("original.txt", File(document.localPath).name)
        assertTrue(generated.readText().contains("&lt;unsafe&gt;"))

        val renamed = File(root, "renamed.txt").apply { writeBytes(first.readBytes()) }
        val duplicate = assertFailsWith<DuplicateDocument> {
            importer.importDocument(renamed, detector.detect(renamed), renamed.name)
        }
        assertEquals(document.id, duplicate.document.id)
    }

    @Test
    fun `concurrent duplicate imports create one stable document`() = runBlocking {
        val files = (1..4).map { index -> File(root, "duplicate-$index.txt").apply { writeText("same bytes") } }
        val outcomes = coroutineScope {
            files.map { file -> async { runCatching { importer.importDocument(file, detector.detect(file), file.name) } } }.awaitAll()
        }
        val imported = outcomes.mapNotNull { it.getOrNull() }
        val duplicates = outcomes.mapNotNull { it.exceptionOrNull() as? DuplicateDocument }
        assertEquals(1, imported.size)
        assertEquals(3, duplicates.size)
        assertTrue(duplicates.all { it.document.id == imported.single().id })
        assertEquals(imported.single().id, repository.getDocumentByHash(imported.single().contentHash)?.id)
    }

    @Test
    fun `html sanitization strips active and remote content and copies safe local assets`() = runBlocking {
        val image = File(root, "cover.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 0x50, 0x4e, 0x47)) }
        val source = File(root, "unsafe.html").apply {
            writeText("""<!doctype html><html><head><title>Safe title</title></head><body onload='x()'><script>x()</script><form><input></form><iframe src='x'></iframe><a href='javascript:x'>bad</a><a href='https://example.com'>remote</a><img src='//example.com/a.png'><img src='cover.png'><img src='../outside.png'><p>kept</p></body></html>""")
        }
        val document = importer.importDocument(source, detector.detect(source), source.name)
        val generated = File(platform.fileSystem.getDocumentGeneratedIndexPath(document.id)).readText()
        val parsed = Jsoup.parse(generated)
        assertEquals("Safe title", document.title)
        assertTrue(parsed.select("script,form,input,iframe").isEmpty())
        assertTrue(parsed.select("[onload]").isEmpty())
        assertTrue(parsed.select("a[href]").isEmpty())
        val retained = parsed.select("img[src]")
        assertEquals(1, retained.size)
        assertEquals("assets/image-1.png", retained.single().attr("src"))
        assertEquals(image.readBytes().toList(), File(platform.fileSystem.getDocumentAssetsDir(document.id), "image-1.png").readBytes().toList())
        assertEquals("kept", parsed.selectFirst("p")?.text())
    }

    @Test
    fun `pdf validation handles metadata encryption malformed and page bound`() = runBlocking {
        val valid = File(root, "valid.pdf").apply { writeText(minimalPdf(title = "PDF title", author = "PDF author")) }
        val document = importer.importDocument(valid, detector.detect(valid), valid.name)
        assertEquals(1, document.pageCount)
        assertEquals("PDF title", document.title)
        assertEquals("PDF author", document.author)

        val encrypted = File(root, "encrypted.pdf").apply { writeText(minimalPdf(extra = "/Encrypt 9 0 R")) }
        assertFailsWith<EncryptedContent> { importer.importDocument(encrypted, detector.detect(encrypted), encrypted.name) }
        val malformed = File(root, "malformed.pdf").apply { writeText("%PDF-1.7\n/Type /Page") }
        assertFailsWith<CorruptContent> { importer.importDocument(malformed, detector.detect(malformed), malformed.name) }
        val huge = File(root, "huge.pdf").apply { writeText(minimalPdf(extra = "/Count 20001")) }
        assertFailsWith<ContentTooLarge> { importer.importDocument(huge, detector.detect(huge), huge.name) }
        Unit
    }
}
