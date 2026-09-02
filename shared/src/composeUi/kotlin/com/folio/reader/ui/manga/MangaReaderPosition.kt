package com.folio.reader.ui.manga

import com.folio.reader.manga.MangaChapter

// Reader position helpers: slot resolution, stable page keys, auto-extend checks.

private const val EXTEND_FORWARD_WINDOW = 6

fun MangaReaderViewModel.pageKey(index: Int): String {
        for (slot in withSlots { toList() }) {
            val local = index - slot.startIndex
            if (local in slot.pages.indices) return "${slot.chapter.id}:$local"
        }
        return "unknown:$index"
    }

fun MangaReaderViewModel.seekLocal(local: Int) {
        val activeSlot = activeSlot() ?: return
        val combined = activeSlot.startIndex + local
        onPageChanged(combined)
        seekChannel.trySend(combined)
    }

fun MangaReaderViewModel.previousBeyond(): MangaChapter? {
        val firstSlotChapterId = withSlots { firstOrNull()?.chapter?.id } ?: return null
        val navIdx = navList.indexOfFirst { it.id == firstSlotChapterId }
        return if (navIdx > 0) navList[navIdx - 1] else null
    }

fun MangaReaderViewModel.activeSlotForDisplay(): ChapterSlot? = activeSlot()

fun MangaReaderViewModel.isAtEndOfNavList(): Boolean = isAtEndOfNav

internal fun MangaReaderViewModel.activeSlot(): ChapterSlot? {
        val idx = currentIndex.value
        for (slot in withSlots { toList() }) {
            if (idx in slot.startIndex until slot.startIndex + slot.pages.size) return slot
        }
        return withSlots { lastOrNull() }
    }

internal fun MangaReaderViewModel.slotForIndex(index: Int): ChapterSlot? {
        for (slot in withSlots { toList() }) {
            if (index in slot.startIndex until slot.startIndex + slot.pages.size) return slot
        }
        return null
    }

    /**
     * The webtoon reports the topmost visible page by its stable key, so position tracks
     * the viewport — not item composition — and survives chapter prepends without races.
     */
fun MangaReaderViewModel.onPageKeyVisible(key: String) {
        val index = indexForPageKey(key) ?: return
        onPageChanged(index)
    }

private fun MangaReaderViewModel.indexForPageKey(key: String): Int? {
        val sep = key.lastIndexOf(':')
        if (sep <= 0 || sep == key.length - 1) return null
        val local = key.substring(sep + 1).toIntOrNull() ?: return null
        val chapterKey = key.substring(0, sep)
        val slot = withSlots { firstOrNull { it.chapter.id == chapterKey } } ?: return null
        if (local !in slot.pages.indices) return null
        return slot.startIndex + local
    }

internal fun MangaReaderViewModel.checkExtensions(index: Int) {
        val lastSlot = withSlots { lastOrNull() } ?: return
        val lastLocal = index - lastSlot.startIndex
        if (lastSlot.pages.isNotEmpty() && lastLocal >= lastSlot.pages.size - EXTEND_FORWARD_WINDOW) {
            extendForward()
        }
        if (mode.value == MangaReaderMode.WEBTOON) {
            val firstSlot = withSlots { firstOrNull() } ?: return
            val firstLocal = index - firstSlot.startIndex
            if (firstLocal <= 1) {
                extendBackward()
            }
        }
    }
