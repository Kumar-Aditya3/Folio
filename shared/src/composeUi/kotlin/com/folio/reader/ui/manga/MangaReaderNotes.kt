package com.folio.reader.ui.manga

import kotlinx.coroutines.launch

suspend fun MangaReaderViewModel.chapterNotes(): List<com.folio.reader.manga.MangaNote> =
        chapter.value?.let { noteRepo.notesForChapter(it.id) } ?: emptyList()

fun MangaReaderViewModel.saveNote(content: String, pageIndex: Int, existing: com.folio.reader.manga.MangaNote?) {
        val c = chapter.value ?: return
        val m = manga.value ?: return
        scope.launch {
            val now = kotlinx.datetime.Clock.System.now()
            noteRepo.upsert(
                com.folio.reader.manga.MangaNote(
                    id = existing?.id ?: java.util.UUID.randomUUID().toString(),
                    mangaId = m.id,
                    chapterId = c.id,
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
