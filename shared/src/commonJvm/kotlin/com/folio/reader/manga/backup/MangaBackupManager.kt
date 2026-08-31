package com.folio.reader.manga.backup

import com.folio.reader.manga.ChapterNumberParser
import com.folio.reader.manga.MangaCategory
import com.folio.reader.manga.MangaChapter
import com.folio.reader.manga.MangaEntry
import com.folio.reader.manga.MangaStatus
import com.folio.reader.manga.MangaCategoryRepository
import com.folio.reader.manga.MangaChapterRepository
import com.folio.reader.manga.MangaHistoryRepository
import com.folio.reader.manga.MangaRepository
import com.folio.reader.manga.chapterId
import com.folio.reader.manga.mangaId
import kotlinx.coroutines.flow.first
import kotlinx.datetime.Instant
import kotlinx.serialization.protobuf.ProtoBuf
import java.io.File
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream

/**
 * Two-way Mihon backup interop: restores a Mihon/Tachiyomi `.backup` (protobuf, optionally
 * gzipped) into the Folio manga library, and exports the Folio manga library as a
 * Mihon-restorable `.backup`.
 */
class MangaBackupManager(
    private val mangaRepo: MangaRepository,
    private val chapterRepo: MangaChapterRepository,
    private val categoryRepo: MangaCategoryRepository,
    private val historyRepo: MangaHistoryRepository,
) {

    data class ImportResult(
        val manga: Int,
        val chapters: Int,
        val categories: Int,
    )

    suspend fun importFromMihonBackup(file: File): ImportResult {
        val raw = file.readBytes()
        val bytes = if (raw.size > 2 && raw[0] == 0x1f.toByte() && raw[1] == 0x8b.toByte()) {
            GZIPInputStream(raw.inputStream()).use { it.readBytes() }
        } else {
            raw
        }
        val backup = ProtoBuf.decodeFromByteArray(Backup.serializer(), bytes)

        // Categories first (dedupe by name against what already exists).
        val existing = categoryRepo.observeCategories().first()
        var created = 0
        for (bc in backup.backupCategories) {
            if (existing.none { it.name == bc.name }) {
                categoryRepo.create(bc.name)
                created++
            }
        }
        val allCategories = categoryRepo.observeCategories().first()

        var mangaCount = 0
        var chapterCount = 0
        for (bm in backup.backupManga) {
            val id = mangaId(bm.source, bm.url)
            mangaRepo.upsert(
                MangaEntry(
                    id = id,
                    sourceId = bm.source,
                    sourceName = backup.backupSources.firstOrNull { it.sourceId == bm.source }?.name ?: "Source",
                    url = bm.url,
                    title = bm.title,
                    author = bm.author,
                    artist = bm.artist,
                    description = bm.description,
                    genres = bm.genre,
                    status = MangaStatus.fromValue(bm.status),
                    thumbnailUrl = bm.thumbnailUrl,
                    inLibrary = bm.favorite,
                    initialized = bm.initialized,
                    addedAt = if (bm.dateAdded > 0) Instant.fromEpochMilliseconds(bm.dateAdded) else Instant.fromEpochMilliseconds(0),
                ),
                emitSyncEvent = true,
            )
            mangaCount++

            val chapters = bm.chapters.mapIndexed { index, bc ->
                MangaChapter(
                    id = chapterId(id, bc.url),
                    mangaId = id,
                    url = bc.url,
                    name = bc.name,
                    scanlator = bc.scanlator,
                    chapterNumber =
                        if (bc.chapterNumber >= 0f) bc.chapterNumber
                        else ChapterNumberParser.parse(bc.name),
                    dateUpload = bc.dateUpload,
                    sortOrder = bc.sourceOrder.toInt().takeIf { it != 0 } ?: index,
                    read = bc.read,
                    bookmarked = bc.bookmark,
                    lastPageRead = bc.lastPageRead.toInt(),
                )
            }
            if (chapters.isNotEmpty()) {
                chapterRepo.replaceChapters(id, chapters)
                chapterCount += chapters.size
            }

            val assignedNames = bm.categories.mapNotNull { idx -> backup.backupCategories.getOrNull(idx.toInt())?.name }
            if (assignedNames.isNotEmpty()) {
                categoryRepo.assign(id, allCategories.filter { it.name in assignedNames }.map { it.id }.toSet())
            } else if (bm.favorite) {
                // No categories in the backup: land on the default shelf so the manga
                // stays visible now that there is no virtual All bucket.
                categoryRepo.ensureMembership(id)
            }

            for (bh in bm.history) {
                chapters.firstOrNull { it.url == bh.url }?.let { historyRepo.record(id, it.id) }
            }
        }
        return ImportResult(mangaCount, chapterCount, created)
    }

    suspend fun exportToMihonBackup(file: File): Int {
        val library = mangaRepo.observeLibrary().first()
        val categories = categoryRepo.observeCategories().first()
        val catIndex = categories.withIndex().associate { (i, c) -> c.name to i.toLong() }

        val backupManga = mutableListOf<BackupManga>()
        for (m in library.filter { it.inLibrary }) {
            val chapters = chapterRepo.getChapters(m.id)
            val assigned = categoryRepo.categoriesFor(m.id)
            val catNames = categories.filter { it.id in assigned }.map { it.name }
            backupManga += BackupManga(
                source = m.sourceId,
                url = m.url,
                title = m.title,
                artist = m.artist,
                author = m.author,
                description = m.description,
                genre = m.genres,
                status = m.status.value,
                thumbnailUrl = m.thumbnailUrl,
                dateAdded = m.addedAt.toEpochMilliseconds(),
                favorite = m.inLibrary,
                initialized = m.initialized,
                categories = catNames.mapNotNull { catIndex[it] },
                chapters = chapters.map { c ->
                    BackupChapter(
                        url = c.url,
                        name = c.name,
                        scanlator = c.scanlator,
                        read = c.read,
                        bookmark = c.bookmarked,
                        lastPageRead = c.lastPageRead.toLong(),
                        dateUpload = c.dateUpload,
                        chapterNumber = c.chapterNumber,
                        sourceOrder = c.sortOrder.toLong(),
                    )
                },
            )
        }

        val backup = Backup(
            backupManga = backupManga,
            backupCategories = categories.mapIndexed { i, c -> BackupCategory(name = c.name, order = i.toLong(), id = i.toLong()) },
            backupSources = library.filter { it.inLibrary }.distinctBy { it.sourceId }
                .map { BackupSource(name = it.sourceName, sourceId = it.sourceId) },
        )

        val encoded = ProtoBuf.encodeToByteArray(Backup.serializer(), backup)
        file.outputStream().use { fos -> GZIPOutputStream(fos).use { it.write(encoded) } }
        return backupManga.size
    }
}
