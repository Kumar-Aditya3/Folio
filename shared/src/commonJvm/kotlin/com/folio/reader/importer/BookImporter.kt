package com.folio.reader.importer

import com.folio.reader.database.BookRepository
import com.folio.reader.database.ReadingPositionRepository
import com.folio.reader.epub.EpubParser
import com.folio.reader.model.Book
import com.folio.reader.model.Chapter
import com.folio.reader.model.CloudState
import com.folio.reader.model.FormattingMode
import com.folio.reader.model.ParsedEpub
import com.folio.reader.platform.FolioPlatform
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.withContext
import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import java.io.File
import java.util.UUID

class BookImporter(
    private val platform: FolioPlatform,
    private val epubParser: EpubParser,
    private val bookRepository: BookRepository,
    private val positionRepository: ReadingPositionRepository,
    private val searchIndexer: SearchIndexer,
    private val hashUtil: com.folio.reader.platform.FileHasher
) {
    suspend fun importEpub(filePath: String): Result<Book> {
        return withContext(Dispatchers.IO) {
            try {
                println("Import: Starting import of $filePath")
                
                // 1. Calculate hash
                println("Import: Calculating file hash...")
                val hash = hashUtil.sha256File(filePath)

                // 2. Deterministic book ID based on file content hash so identical books across devices get identical IDs
                val deterministicBookId = UUID.nameUUIDFromBytes("folio_epub_$hash".toByteArray()).toString()

                // 3. Check for duplicates or existing remote-synced book record
                println("Import: Checking for duplicates...")
                val existingByHash = bookRepository.getBookByEpubHash(hash)
                if (existingByHash != null) {
                    val fileExists = File(platform.fileSystem.getBookEpubPath(existingByHash.id)).exists()
                    if (fileExists) {
                        println("Import: Duplicate detected (same file hash)")
                        return@withContext Result.failure(DuplicateBookException("Book already imported (same file)", existingByHash))
                    }
                    println("Import: Linking local EPUB file to existing synced metadata record (${existingByHash.id})")
                }

                // 4. Parse EPUB
                println("Import: Parsing EPUB...")
                val parsed = epubParser.parseEpub(filePath)
                println("Import: Parsed ${parsed.chapters.size} chapters, ${parsed.totalWords} words")

                // 5. Check for metadata duplicates (ISBN) if no hash match was found
                if (existingByHash == null && parsed.metadata.isbn != null) {
                    val existingByIsbn = bookRepository.getBookByIsbn(parsed.metadata.isbn!!)
                    if (existingByIsbn != null) {
                        val fileExists = File(platform.fileSystem.getBookEpubPath(existingByIsbn.id)).exists()
                        if (fileExists) {
                            println("Import: Duplicate detected (same ISBN)")
                            return@withContext Result.failure(DuplicateBookException("Book with same ISBN exists", existingByIsbn))
                        }
                    }
                }

                // 6. Book ID
                val bookId = existingByHash?.id ?: deterministicBookId
                println("Import: Using book ID: $bookId")

                // 6. Copy to library
                println("Import: Copying file to library...")
                val sourceFile = File(filePath)
                val epubPath = platform.fileSystem.copyToLibrary(sourceFile, bookId)

                // 7. Extract cover
                println("Import: Extracting cover...")
                val coverPath = if (parsed.coverData != null) {
                    val coverFile = File(platform.fileSystem.getBookDir(bookId), "cover.jpg")
                    coverFile.writeBytes(parsed.coverData!!)
                    coverFile.absolutePath
                } else null

                // 8. Create book record
                println("Import: Creating book record...")
                val book = Book(
                    id = bookId,
                    title = parsed.metadata.title,
                    subtitle = parsed.metadata.subtitle,
                    authors = parsed.metadata.authors,
                    publisher = parsed.metadata.publisher,
                    language = parsed.metadata.language,
                    isbn = parsed.metadata.isbn,
                    description = parsed.metadata.description,
                    publicationDate = parsed.metadata.publicationDate,
                    coverPath = coverPath,
                    epubHash = hash,
                    epubFileSize = sourceFile.length(),
                    addedAt = Clock.System.now(),
                    totalCharacters = parsed.totalCharacters,
                    totalWords = parsed.totalWords,
                    chapterCount = parsed.chapters.size,
                    formattingMode = parsed.chapters.firstOrNull()?.let { _ -> com.folio.reader.model.FormattingMode.HYBRID } ?: com.folio.reader.model.FormattingMode.HYBRID
                )

                // 9. Update chapters with book ID
                val chaptersWithBookId = parsed.chapters.map { it.copy(bookId = bookId) }

                // 10. Save to database
                println("Import: Saving to database...")
                bookRepository.insertBook(book)
                bookRepository.insertChapters(bookId, chaptersWithBookId)

                // 11. Index for search — one bulk transaction (150-chapter books
                // were doing 150 separate commits, hanging the import for minutes).
                println("Import: Building search index (${chaptersWithBookId.size} chapters)...")
                val indexEntries = chaptersWithBookId.mapNotNull { chapter ->
                    val html = parsed.rawHtmlByHref[chapter.href] ?: ""
                    val text = com.folio.reader.importer.SearchIndexer.extractPlainText(html)
                    if (text.isBlank()) null
                    else com.folio.reader.database.ChapterIndexEntry(chapter.id, chapter.spineIndex, chapter.title, text)
                }
                
                // Run indexing in a separate coroutine scope to avoid blocking
                println("Import: Indexing ${indexEntries.size} chapters in bulk...")
                searchIndexer.indexChaptersBulk(bookId, indexEntries)
                println("Import: Search indexing complete")

                // 12. Save metadata JSON
                println("Import: Saving metadata...")
                saveMetadataJson(bookId, parsed)

                println("Import: Successfully imported '${book.title}'")
                return@withContext Result.success(book)
            } catch (e: Exception) {
                println("Import: FAILED - ${e.message}")
                e.printStackTrace()
                return@withContext Result.failure(e)
            }
        }
    }

    private fun saveMetadataJson(bookId: String, parsed: ParsedEpub) {
        val metadataFile = File(platform.fileSystem.getBookMetadataPath(bookId))
        val json = com.folio.reader.util.JsonUtils.toJson(parsed)
        metadataFile.writeText(json)
    }

    suspend fun importMultiple(filePaths: List<String>): List<Result<Book>> {
        return coroutineScope {
            filePaths.map { path -> async { importEpub(path) } }.awaitAll()
        }
    }
}

class DuplicateBookException(
    message: String,
    val existingBook: Book
) : Exception(message)

class SearchIndexer(
    private val searchRepository: com.folio.reader.database.SearchRepository
) {
    suspend fun indexChapter(bookId: String, chapterId: String, spineIndex: Int, title: String, html: String) {
        val text = extractPlainText(html)
        searchRepository.indexChapter(bookId, chapterId, spineIndex, title, text)
    }

    suspend fun indexChaptersBulk(bookId: String, entries: List<com.folio.reader.database.ChapterIndexEntry>) {
        searchRepository.indexChaptersBulk(bookId, entries)
    }

    companion object {
        fun extractPlainText(html: String): String {
            return html
                .replace(Regex("(?s)<script.*?</script>"), "")
                .replace(Regex("(?s)<style.*?</style>"), "")
                .replace(Regex("<[^>]+>"), " ")
                .replace(Regex("\\s+"), " ")
                .trim()
        }
    }

    private fun extractText(html: String): String = extractPlainText(html)

    suspend fun reindexBook(bookId: String, chapters: List<Chapter>, htmlByHref: Map<String, String>) {
        searchRepository.deleteIndexForBook(bookId)
        val entries = chapters.mapNotNull { chapter ->
            val html = htmlByHref[chapter.href] ?: ""
            val text = extractPlainText(html)
            if (text.isBlank()) null
            else com.folio.reader.database.ChapterIndexEntry(chapter.id, chapter.spineIndex, chapter.title, text)
        }
        searchRepository.indexChaptersBulk(bookId, entries)
    }
}