package com.folio.reader.importer

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.sync.Semaphore
import kotlinx.coroutines.sync.withPermit
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException

class IncomingContentCoordinator(
    private val detector: DocumentFormatDetector,
    private val bookImporter: BookImporter,
    private val documentImporter: DocumentImporter
) {
    private val importPermits = Semaphore(2)

    suspend fun import(path: String, filename: String? = null, mimeType: String? = null): IncomingContentResult =
        importPermits.withPermit { importWithPermit(path, filename, mimeType) }

    private suspend fun importWithPermit(path: String, filename: String?, mimeType: String?): IncomingContentResult = withContext(Dispatchers.IO) {
        try {
            val file = File(path)
            val detected = detector.detect(file, filename ?: file.name, mimeType)
            if (detected.format == IncomingFormat.EPUB) {
                val result = bookImporter.importEpub(file.absolutePath)
                result.fold(
                    onSuccess = { IncomingContentResult.ImportedBook(it) },
                    onFailure = { error ->
                        if (error is DuplicateBookException) IncomingContentResult.DuplicateBook(error.existingBook)
                        else mapFailure(error)
                    }
                )
            } else {
                IncomingContentResult.ImportedDocument(documentImporter.importDocument(file, detected, filename ?: file.name))
            }
        } catch (e: DuplicateDocument) {
            IncomingContentResult.DuplicateDocument(e.document)
        } catch (e: Throwable) {
            mapFailure(e)
        }
    }

    suspend fun importMany(items: List<IncomingContent>): List<IncomingContentResult> = coroutineScope {
        items.map { item -> async { import(item.path, item.filename, item.mimeType) } }.awaitAll()
    }

    private fun mapFailure(error: Throwable): IncomingContentResult = when (error) {
        is UnsupportedContent -> IncomingContentResult.Unsupported(error.message ?: "Unsupported content")
        is UnsafeContent, is SecurityException -> IncomingContentResult.Unsafe(error.message ?: "Unsafe content")
        is EncryptedContent -> IncomingContentResult.Encrypted(error.message ?: "Encrypted content")
        is ContentTooLarge -> IncomingContentResult.TooLarge(error.message ?: "Content is too large")
        is CorruptContent, is java.util.zip.ZipException -> IncomingContentResult.Corrupt(error.message ?: "Corrupt content")
        is IOException -> IncomingContentResult.IoError(error.message ?: "I/O failure", error)
        else -> IncomingContentResult.IoError(error.message ?: "Import failed", error)
    }
}

data class IncomingContent(val path: String, val filename: String? = null, val mimeType: String? = null)
