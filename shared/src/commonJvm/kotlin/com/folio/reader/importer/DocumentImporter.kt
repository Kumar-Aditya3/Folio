package com.folio.reader.importer

import com.folio.reader.database.DocumentCategoryRepository
import com.folio.reader.database.DocumentRepository
import com.folio.reader.model.Document
import com.folio.reader.platform.FolioPlatform
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap

class DocumentImporter(
    private val platform: FolioPlatform,
    private val repository: DocumentRepository,
    private val categoryRepository: DocumentCategoryRepository,
    private val thumbnailRenderer: suspend (
        path: String,
        targetWidthPx: Int,
        targetHeightPx: Int
    ) -> ByteArray?
) {
    private val hashLocks = ConcurrentHashMap<String, Mutex>()

    suspend fun importDocument(file: File, detected: DetectedContent, originalFilename: String = file.name): Document = withContext(Dispatchers.IO) {
        val format = detected.format
        if (format.documentFormat == null) throw UnsupportedContent("EPUB must use BookImporter")
        val observedSize = file.length()
        if (observedSize > MAX_SOURCE_BYTES) throw ContentTooLarge("Input exceeds 512 MiB")
        if (format in setOf(IncomingFormat.DOCX, IncomingFormat.ODT) && observedSize > MAX_OFFICE_BYTES) {
            throw ContentTooLarge("Office document exceeds 100 MiB")
        }
        val fallbackTitle = originalFilename.substringBeforeLast('.').ifBlank { "Untitled" }
        val importToken = UUID.randomUUID().toString()
        var documentId: String? = null
        var databaseWritten = false
        try {
            val maxBytes = if (format in setOf(IncomingFormat.DOCX, IncomingFormat.ODT)) MAX_OFFICE_BYTES else MAX_SOURCE_BYTES
            val staged = try {
                platform.fileSystem.stageDocumentCopy(file, importToken, format.canonicalExtension, maxBytes)
            } catch (_: com.folio.reader.platform.StagedCopyTooLargeException) {
                throw ContentTooLarge("Input exceeds its size limit")
            }
            val lock = hashLocks.computeIfAbsent(staged.sha256) { Mutex() }
            lock.withLock {
                try {
                    val duplicate = repository.getDocumentByHash(staged.sha256)
                    if (duplicate != null) throw DuplicateDocument(duplicate)
                    documentId = UUID.nameUUIDFromBytes("folio_document_${staged.sha256}".toByteArray()).toString()
                    val processed = process(File(staged.path), format, fallbackTitle, file.parentFile)
                    val generatedBytes = processed.generatedHtml?.toByteArray(Charsets.UTF_8)
                    if (generatedBytes != null && generatedBytes.size > MAX_GENERATED_HTML_BYTES) throw ContentTooLarge("Generated HTML exceeds 50 MiB")
                    val stagedRoot = File(staged.path).parentFile
                    processed.generatedHtml?.let {
                        File(stagedRoot, "generated/index.html").apply {
                            parentFile.mkdirs()
                            writeText(it, Charsets.UTF_8)
                        }
                    }
                    if (processed.assets.isNotEmpty()) {
                        val assetsDir = File(stagedRoot, "generated/assets").apply { mkdirs() }
                        processed.assets.forEach { (name, bytes) -> File(assetsDir, name).writeBytes(bytes) }
                    }
                    val localPath = platform.fileSystem.commitStagedDocument(importToken, documentId!!, format.canonicalExtension)
                    val thumbnailPath: String? = if (format == IncomingFormat.PDF) {
                        try {
                            val bytes: ByteArray? =
                                thumbnailRenderer(
                                    localPath,
                                    360,
                                    480
                                )
                            bytes?.let { thumbnailBytes ->
                                platform.fileSystem.getDocumentThumbnailsDir(documentId!!)
                                    .resolve("first-page.png")
                                    .apply { writeBytes(thumbnailBytes) }
                                    .absolutePath
                            }
                        } catch (cancelled: CancellationException) {
                            throw cancelled
                        } catch (_: Throwable) {
                            null
                        }
                    } else {
                        null
                    }
                    val now = Clock.System.now()
                    val document = Document(
                        id = documentId!!,
                        title = processed.title,
                        originalFilename = originalFilename,
                        format = format.documentFormat,
                        mimeType = format.mimeType,
                        contentHash = staged.sha256,
                        byteSize = staged.byteSize,
                        localPath = localPath,
                        thumbnailPath = thumbnailPath,
                        author = processed.author,
                        description = processed.description,
                        pageCount = processed.pageCount,
                        sectionCount = processed.sectionCount,
                        importedAt = now,
                        updatedAt = now
                    )
                    repository.upsertDocument(document)
                    databaseWritten = true
                    categoryRepository.ensureMembership(document.id)
                    document
                } finally {
                    hashLocks.remove(staged.sha256, lock)
                }
            }
        } catch (e: Throwable) {
            val failedDocumentId = documentId
            if (databaseWritten && failedDocumentId != null) runCatching { repository.deleteDocument(failedDocumentId) }
            if (failedDocumentId != null) runCatching { platform.fileSystem.deleteDocumentFiles(failedDocumentId) }
            runCatching { platform.fileSystem.deleteDocumentFiles(importToken) }
            throw e
        }
    }

    private fun process(file: File, format: IncomingFormat, title: String, sourceDir: File?): ProcessedDocument = when (format) {
        IncomingFormat.PDF -> PdfProcessor.process(file, title)
        IncomingFormat.TXT -> TextProcessor.process(file.readBytes(), title)
        IncomingFormat.HTML -> {
            val html = decodeUtf8(file.readBytes())
            val parsedTitle = org.jsoup.Jsoup.parse(html).title().takeIf(String::isNotBlank) ?: title
            val sanitized = SafeHtml.sanitize(html, parsedTitle, sourceDir)
            if (sanitized.html.toByteArray().size > MAX_GENERATED_HTML_BYTES) throw ContentTooLarge("Generated HTML exceeds 50 MiB")
            ProcessedDocument(parsedTitle, sectionCount = 1, generatedHtml = sanitized.html, assets = sanitized.assets)
        }
        IncomingFormat.DOCX, IncomingFormat.ODT -> OfficeProcessor.process(file, format, title)
        IncomingFormat.EPUB -> throw UnsupportedContent("EPUB must use BookImporter")
    }

    private fun decodeUtf8(bytes: ByteArray): String = try {
        StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
    } catch (_: Exception) {
        throw CorruptContent("HTML is not valid UTF-8")
    }
}

data class DocumentDeletionResult(val metadataDeleted: Boolean, val filesDeleted: Boolean, val cleanupError: Throwable? = null)

class DocumentDeletionService(
    private val repository: DocumentRepository,
    private val platform: FolioPlatform
) {
    suspend fun delete(documentId: String): DocumentDeletionResult {
        repository.deleteDocument(documentId)
        return try {
            DocumentDeletionResult(true, platform.fileSystem.deleteDocumentFiles(documentId))
        } catch (e: Throwable) {
            DocumentDeletionResult(true, false, e)
        }
    }
}
