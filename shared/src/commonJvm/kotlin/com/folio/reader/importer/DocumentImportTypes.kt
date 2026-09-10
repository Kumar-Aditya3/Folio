package com.folio.reader.importer

import com.folio.reader.model.Book
import com.folio.reader.model.Document
import com.folio.reader.model.DocumentFormat

const val MAX_SOURCE_BYTES: Long = 512L * 1024 * 1024
const val MAX_OFFICE_BYTES: Long = 100L * 1024 * 1024
const val MAX_GENERATED_HTML_BYTES: Long = 50L * 1024 * 1024
const val MAX_ARCHIVE_BYTES: Long = 512L * 1024 * 1024
const val MAX_ARCHIVE_ENTRY_BYTES: Long = 128L * 1024 * 1024
const val MAX_ARCHIVE_ENTRIES = 10_000
const val MAX_COMPRESSION_RATIO = 100L
const val MAX_PDF_PAGES = 20_000

enum class IncomingFormat(val canonicalExtension: String, val mimeType: String, val documentFormat: DocumentFormat?) {
    EPUB("epub", "application/epub+zip", null),
    PDF("pdf", "application/pdf", DocumentFormat.PDF),
    TXT("txt", "text/plain", DocumentFormat.TXT),
    HTML("html", "text/html", DocumentFormat.HTML),
    DOCX("docx", "application/vnd.openxmlformats-officedocument.wordprocessingml.document", DocumentFormat.DOCX),
    ODT("odt", "application/vnd.oasis.opendocument.text", DocumentFormat.ODT)
}

data class DetectedContent(val format: IncomingFormat, val contentEvidence: Boolean)

sealed class IncomingContentResult {
    data class ImportedBook(val book: Book) : IncomingContentResult()
    data class ImportedDocument(val document: Document) : IncomingContentResult()
    data class DuplicateBook(val book: Book) : IncomingContentResult()
    data class DuplicateDocument(val document: Document) : IncomingContentResult()
    data class Unsupported(val reason: String) : IncomingContentResult()
    data class Unsafe(val reason: String) : IncomingContentResult()
    data class Corrupt(val reason: String) : IncomingContentResult()
    data class Encrypted(val reason: String) : IncomingContentResult()
    data class TooLarge(val reason: String) : IncomingContentResult()
    data class IoError(val reason: String, val cause: Throwable? = null) : IncomingContentResult()
}

typealias ImportResult = IncomingContentResult

internal open class ImportFailure(message: String) : Exception(message)
internal class UnsupportedContent(message: String) : ImportFailure(message)
internal class UnsafeContent(message: String) : ImportFailure(message)
internal class CorruptContent(message: String) : ImportFailure(message)
internal class EncryptedContent(message: String) : ImportFailure(message)
internal class ContentTooLarge(message: String) : ImportFailure(message)
internal class DuplicateDocument(val document: Document) : ImportFailure("Document is already imported")

data class ProcessedDocument(
    val title: String,
    val author: String? = null,
    val description: String? = null,
    val pageCount: Int? = null,
    val sectionCount: Int? = null,
    val generatedHtml: String? = null,
    val assets: Map<String, ByteArray> = emptyMap()
)
