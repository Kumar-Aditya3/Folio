package com.folio.reader.ui.manga

import kotlinx.coroutines.launch
import kotlinx.coroutines.CancellationException

suspend fun MangaReaderViewModel.mangaNotes(): List<com.folio.reader.manga.MangaNote> =
        manga.value?.let { noteRepo.notesForManga(it.id) } ?: emptyList()

fun MangaReaderViewModel.openNote(note: com.folio.reader.manga.MangaNote) {
    val currentManga = manga.value ?: return
    if (note.mangaId != currentManga.id) return
    scope.launch {
        try {
            val target = chapterRepo.getChapter(note.chapterId)
            if (target == null || target.mangaId != currentManga.id) {
                error.value = "The chapter for this note is no longer available."
                return@launch
            }
            open(currentManga, target, initialPage = note.pageIndex)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Exception) {
            error.value = e.message ?: "Could not open this saved note."
        }
    }
}

fun MangaReaderViewModel.saveNote(content: String, pageIndex: Int, existing: com.folio.reader.manga.MangaNote?, chapterId: String? = null) {
        val c = chapter.value ?: return
        val m = manga.value ?: return
        scope.launch {
            val now = kotlinx.datetime.Clock.System.now()
            noteRepo.upsert(
                com.folio.reader.manga.MangaNote(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    mangaId = m.id,
                    chapterId = chapterId ?: existing?.chapterId ?: c.id,
                    pageIndex = pageIndex,
                    content = content,
                    createdAt = existing?.createdAt ?: now,
                    updatedAt = now,
                )
            )
            notesRevision.value++
        }
    }

fun MangaReaderViewModel.deleteNote(noteId: String) {
        scope.launch {
            noteRepo.delete(noteId)
            notesRevision.value++
        }
    }
