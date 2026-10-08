package com.folio.reader.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

@Serializable
data class Highlight(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val startLocator: String, // EPUB CFI
    val endLocator: String, // EPUB CFI
    val selectedText: String,
    val color: HighlightColor = HighlightColor.YELLOW,
    val customColor: Int? = null, // ARGB
    val noteId: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null,
    val surroundingTextBefore: String? = null, // For relocation
    val surroundingTextAfter: String? = null
) {
    fun withNote(noteId: String): Highlight {
        return copy(noteId = noteId, updatedAt = Clock.System.now())
    }

    fun delete(): Highlight {
        return copy(isDeleted = true, deletedAt = Clock.System.now(), updatedAt = Clock.System.now())
    }

    fun restore(): Highlight {
        return copy(isDeleted = false, deletedAt = null, updatedAt = Clock.System.now())
    }

    val effectiveColor: Int
        get() = customColor ?: color.argb
}

enum class HighlightColor(val argb: Int, val label: String) {
    YELLOW(0xFFFFFF00.toInt(), "Yellow"),
    BLUE(0xFF007AFF.toInt(), "Blue"),
    GREEN(0xFF34C759.toInt(), "Green"),
    RED(0xFFFF3B30.toInt(), "Red"),
    PURPLE(0xFFAF52DE.toInt(), "Purple"),
    ORANGE(0xFFFF9F0A.toInt(), "Orange"),
    TEAL(0xFF5AC8FA.toInt(), "Teal"),
    PINK(0xFFFF2D92.toInt(), "Pink");

    companion object {
        fun fromArgb(argb: Int): HighlightColor = values().firstOrNull { it.argb == argb } ?: YELLOW
    }
}

@Serializable
data class Note(
    val id: String,
    val bookId: String,
    val chapterId: String?,
    val spineIndex: Int?,
    val locator: String?, // EPUB CFI for position-specific notes
    val content: String,
    val type: NoteType = NoteType.GENERAL,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null
) {
    fun delete(): Note {
        return copy(isDeleted = true, deletedAt = Clock.System.now(), updatedAt = Clock.System.now())
    }

    fun restore(): Note {
        return copy(isDeleted = false, deletedAt = null, updatedAt = Clock.System.now())
    }
}

enum class NoteType(val value: Int) {
    HIGHLIGHT_NOTE(0),
    BOOKMARK_NOTE(1),
    CHAPTER_NOTE(2),
    BOOK_NOTE(3),
    GENERAL(4);

    companion object {
        fun fromValue(value: Int): NoteType = values().firstOrNull { it.value == value } ?: GENERAL
    }
}

@Serializable
data class Bookmark(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val spineIndex: Int,
    val locator: String, // EPUB CFI
    val label: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val deviceId: String,
    val isDeleted: Boolean = false,
    val deletedAt: Instant? = null
) {
    fun delete(): Bookmark {
        return copy(isDeleted = true, deletedAt = Clock.System.now(), updatedAt = Clock.System.now())
    }

    fun restore(): Bookmark {
        return copy(isDeleted = false, deletedAt = null, updatedAt = Clock.System.now())
    }
}

@Serializable
data class Quote(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val highlightId: String,
    val text: String,
    val note: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val deviceId: String
)

@Serializable
data class RevisitItem(
    val id: String,
    val bookId: String,
    val chapterId: String,
    val type: RevisitType,
    val sourceId: String, // highlight_id, bookmark_id, note_id, or chapter_id
    val note: String? = null,
    val createdAt: Instant = Clock.System.now(),
    val resolvedAt: Instant? = null,
    val deviceId: String
) {
    val isResolved: Boolean
        get() = resolvedAt != null

    fun resolve(): RevisitItem {
        return copy(resolvedAt = Clock.System.now())
    }
}

enum class RevisitType(val value: Int) {
    HIGHLIGHT(0),
    BOOKMARK(1),
    NOTE(2),
    CHAPTER(3);

    companion object {
        fun fromValue(value: Int): RevisitType = values().firstOrNull { it.value == value } ?: HIGHLIGHT
    }
}