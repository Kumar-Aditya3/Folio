package com.folio.reader.model

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.datetime.LocalDateTime
import kotlinx.datetime.TimeZone
import kotlin.math.roundToInt
import kotlinx.serialization.Contextual
import kotlinx.serialization.Serializable
import kotlinx.serialization.descriptors.PrimitiveKind
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.CompositeEncoder
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.json.Json

@Serializable
data class Book(
    val id: String,
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publicationDate: Instant? = null,
    val coverPath: String? = null,
    val epubHash: String,
    val epubFileSize: Long,
    val addedAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val lastOpenedAt: Instant? = null,
    val totalCharacters: Long = 0,
    val totalWords: Long = 0,
    val chapterCount: Int = 0,
    val seriesId: String? = null,
    val seriesNumber: Double? = null,
    val status: BookStatus = BookStatus.UNREAD,
    val cloudState: CloudState = CloudState.LOCAL_ONLY,
    val formattingMode: FormattingMode = FormattingMode.HYBRID
) {
    val displayTitle: String
        get() = if (subtitle.isNullOrBlank()) title else "$title: $subtitle"

    val displayAuthor: String
        get() = authors.joinToString(", ")

    val hasSeries: Boolean
        get() = seriesId != null

    val progressPercent: Int
        get() = (normalizedProgress * 100).roundToInt()

    var normalizedProgress: Double = 0.0
        private set

    fun updateProgress(progress: Double) {
        normalizedProgress = progress.coerceIn(0.0, 1.0)
    }
}

enum class BookStatus(val value: Int) {
    UNREAD(0),
    READING(1),
    PAUSED(2),
    FINISHED(3),
    ABANDONED(4);

    companion object {
        fun fromValue(value: Int): BookStatus = values().firstOrNull { it.value == value } ?: UNREAD
    }
}

enum class CloudState(val value: Int) {
    LOCAL_ONLY(0),
    UPLOADING(1),
    SYNCED(2),
    REMOTE_ONLY(3),
    DOWNLOADING(4),
    SYNC_ERROR(5),
    UPLOADING_PROGRESS(6),
    DOWNLOADING_PROGRESS(7);

    companion object {
        fun fromValue(value: Int): CloudState = values().firstOrNull { it.value == value } ?: LOCAL_ONLY
    }
}

enum class FormattingMode(val value: Int) {
    ORIGINAL(0),
    HYBRID(1),
    NORMALIZED(2);

    companion object {
        fun fromValue(value: Int): FormattingMode = values().firstOrNull { it.value == value } ?: HYBRID
    }
}

@Serializable
data class Chapter(
    val id: String,
    val bookId: String,
    val title: String,
    val href: String,
    val spineIndex: Int,
    val level: Int = 0,
    val children: List<Chapter> = emptyList(),
    val characterCount: Long = 0,
    val wordCount: Long = 0,
    val startOffset: Long = 0,
    val endOffset: Long = 0
) {
    val isLeaf: Boolean
        get() = children.isEmpty()

    val hasChildren: Boolean
        get() = children.isNotEmpty()
}

@Serializable
data class Series(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val updatedAt: Instant = Clock.System.now()
)

@Serializable
data class Collection(
    val id: String,
    val name: String,
    val color: Int? = null,
    val sortOrder: Int = 0,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)

@Serializable
data class Tag(
    val id: String,
    val name: String,
    val color: Int? = null,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now()
)

@Serializable
data class EpubMetadata(
    val title: String,
    val subtitle: String? = null,
    val authors: List<String> = emptyList(),
    val publisher: String? = null,
    val language: String? = null,
    val isbn: String? = null,
    val description: String? = null,
    val publicationDate: Instant? = null,
    val coverHref: String? = null,
    val subject: List<String> = emptyList(),
    val contributor: List<String> = emptyList(),
    val date: String? = null,
    val format: String? = null,
    val identifier: List<String> = emptyList(),
    val rights: String? = null,
    val source: String? = null,
    val type: String? = null
)

@Serializable
data class EpubManifestItem(
    val id: String,
    val href: String,
    val mediaType: String,
    val properties: String? = null
)

@Serializable
data class EpubSpineItem(
    val idref: String,
    val linear: Boolean = true,
    val properties: String? = null
)

@Serializable
data class EpubTocItem(
    val label: String,
    val href: String,
    val children: List<EpubTocItem> = emptyList(),
    val playOrder: Int? = null,
    val level: Int = 0
)

@Serializable
data class ParsedEpub(
    val metadata: EpubMetadata,
    val manifest: List<EpubManifestItem>,
    val spine: List<EpubSpineItem>,
    val toc: List<EpubTocItem>,
    val coverData: ByteArray? = null,
    val coverMimeType: String? = null,
    val chapters: List<Chapter> = emptyList(),
    val totalCharacters: Long = 0,
    val totalWords: Long = 0,
    val rawHtmlByHref: Map<String, String> = emptyMap()
)