package com.folio.reader.manga

import kotlinx.datetime.Clock
import kotlinx.datetime.Instant
import kotlinx.serialization.Serializable

/**
 * Folio's manga library domain. Books (EPUB) and manga live in completely separate
 * categories; this file defines the manga-side models used by both platforms. The
 * Android app powers sources through the vendored Mihon extension runtime, while
 * desktop uses the built-in local source.
 */

/** Stable id for a manga entry: "<sourceId>:<source-relative url>". */
fun mangaId(sourceId: Long, url: String): String = "$sourceId:$url"

/** Stable id for a chapter within a manga. */
fun chapterId(mangaId: String, url: String): String = "$mangaId|$url"

const val LOCAL_SOURCE_ID: Long = 0L

/**
 * Settings key for the user-picked manga downloads location. Blank = app default dir.
 * Android stores a SAF tree URI string, desktop stores an absolute directory path.
 */
const val KEY_MANGA_DOWNLOADS_LOCATION = "manga.downloads.location"

data class MangaEntry(
    val id: String,
    val sourceId: Long,
    val sourceName: String,
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val thumbnailUrl: String? = null,
    val coverPath: String? = null,
    /** Whether the manga is in the user's library. Maps to the legacy `favorite` DB/sync field. */
    val inLibrary: Boolean = false,
    val initialized: Boolean = false,
    val addedAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
    val lastReadAt: Instant? = null,
) {
    val isLocal: Boolean get() = sourceId == LOCAL_SOURCE_ID
}

enum class MangaStatus(val value: Int) {
    UNKNOWN(0),
    ONGOING(1),
    COMPLETED(2),
    LICENSED(3),
    PUBLISHING_FINISHED(4),
    CANCELLED(5),
    ON_HIATUS(6);

    companion object {
        fun fromValue(value: Int): MangaStatus = entries.firstOrNull { it.value == value } ?: UNKNOWN
    }
}

data class MangaChapter(
    val id: String,
    val mangaId: String,
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val chapterNumber: Float = -1f,
    val dateUpload: Long = 0L,
    val sortOrder: Int = 0,
    val read: Boolean = false,
    val bookmarked: Boolean = false,
    val lastPageRead: Int = 0,
    val downloadedPages: Int = 0,
    val totalPages: Int = 0,
    val updatedAt: Instant = Clock.System.now(),
)

@Serializable
data class MangaNote(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val pageIndex: Int,
    val content: String,
    val createdAt: Instant = Clock.System.now(),
    val updatedAt: Instant = Clock.System.now(),
)

@Serializable
data class MangaCategory(
    val id: String,
    val name: String,
    val sortOrder: Int = 0,
    val updatedAt: Instant = Clock.System.now(),
) {
    companion object {
        /**
         * Stable id of the built-in default category. Every device seeds it with this
         * same id, so sync merges all devices' defaults into one category.
         */
        const val MAIN_ID = "main"
        const val MAIN_NAME = "Main"
    }
}

data class MangaTopEntry(
    val mangaId: String,
    val title: String,
    val readMinutes: Long,
)

data class MangaStatistics(
    val libraryCount: Int = 0,
    val completedCount: Int = 0,
    val readChapters: Int = 0,
    val unreadChapters: Int = 0,
    val downloadedChapters: Int = 0,
    val bookmarkedChapters: Int = 0,
    val notesCount: Int = 0,
    val totalReadMinutes: Long = 0L,
    /** Distinct local days on which at least one chapter was marked read. */
    val readActiveDays: Int = 0,
    val weekReadChapters: List<Int> = List(7) { 0 },
    val weekLabels: List<String> = List(7) { "" },
    val topManga: List<MangaTopEntry> = emptyList(),
) {
    val hasData: Boolean get() = readChapters > 0 || totalReadMinutes > 0 || libraryCount > 0
}

data class MangaLastRead(
    val chapterName: String,
    val lastPage: Int,
    val totalPages: Int,
)

data class MangaHistoryItem(
    val mangaId: String,
    val chapterId: String?,
    val readAt: Instant,
    val manga: MangaEntry? = null,
    val chapterName: String? = null,
)

/** One row per manga remembering its last-read chapter; local-only, never synced. */
data class MangaHistoryEntry(
    val mangaId: String,
    val title: String,
    val coverUrl: String? = null,
    val coverPath: String? = null,
    val sourceId: Long = 0L,
    val sourceName: String? = null,
    val chapterName: String,
    val updatedAt: Long,
    val inLibrary: Boolean = false,
)

data class MangaSourceInfo(
    val id: Long,
    val name: String,
    val lang: String,
    val isLocal: Boolean = false,
    val extensionPkg: String? = null,
    val supportsLatest: Boolean = false,
    val hasFilters: Boolean = false,
)

@Serializable
data class MangaRepoInfo(
    val name: String,
    val baseUrl: String,
    val indexUrl: String,
    val signingKey: String? = null,
)

data class ExtensionEntry(
    val pkgName: String,
    val name: String,
    val versionName: String,
    val versionCode: Long,
    val libVersion: Double,
    val lang: String?,
    val isNsfw: Boolean,
    val isInstalled: Boolean = false,
    val hasUpdate: Boolean = false,
    val isObsolete: Boolean = false,
    val isUntrusted: Boolean = false,
    val signatureHash: String = "",
    val sourceNames: List<String> = emptyList(),
)

enum class ExtensionInstallStep {
    Idle,
    Pending,
    Downloading,
    Installing,
    Installed,
    Error,
}

/** Cross-platform mirror of Mihon's source filter tree. */
@Serializable
sealed class MangaFilter {
    @Serializable
    data class Header(val name: String) : MangaFilter()

    @Serializable
    data class Separator(val name: String) : MangaFilter()

    @Serializable
    data class Text(val name: String, val state: String = "") : MangaFilter()

    @Serializable
    data class CheckBox(val name: String, val state: Boolean = false) : MangaFilter()

    @Serializable
    data class TriState(val name: String, val state: Int = 0) : MangaFilter()

    @Serializable
    data class Select(val name: String, val values: List<String>, val state: Int = 0) : MangaFilter()

    @Serializable
    data class Sort(
        val name: String,
        val values: List<String>,
        val selection: SortSelection? = null,
    ) : MangaFilter() {
        @Serializable
        data class SortSelection(val index: Int, val ascending: Boolean)
    }

    @Serializable
    data class Group(val name: String, val filters: List<MangaFilter>) : MangaFilter()
}

enum class BrowseMode { POPULAR, LATEST }

data class MangaBrowseItem(
    val url: String,
    val title: String,
    val thumbnailUrl: String? = null,
)

data class MangaBrowsePage(
    val items: List<MangaBrowseItem>,
    val hasNextPage: Boolean,
)

data class MangaDetail(
    val url: String,
    val title: String,
    val author: String? = null,
    val artist: String? = null,
    val description: String? = null,
    val genres: List<String> = emptyList(),
    val status: MangaStatus = MangaStatus.UNKNOWN,
    val thumbnailUrl: String? = null,
)

data class MangaChapterRef(
    val url: String,
    val name: String,
    val scanlator: String? = null,
    val chapterNumber: Float = -1f,
    val dateUpload: Long = 0L,
)

data class MangaPageRef(
    val index: Int,
    val url: String = "",
    val imageUrl: String? = null,
)

data class MangaImageData(
    val bytes: ByteArray,
    val mimeType: String? = null,
) {
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is MangaImageData) return false
        return bytes.contentEquals(other.bytes) && mimeType == other.mimeType
    }

    override fun hashCode(): Int = bytes.contentHashCode() * 31 + (mimeType?.hashCode() ?: 0)
}

enum class MangaDownloadStatus(val value: Int) {
    QUEUED(0),
    DOWNLOADING(1),
    DOWNLOADED(2),
    ERROR(3);

    companion object {
        fun fromValue(value: Int): MangaDownloadStatus = entries.firstOrNull { it.value == value } ?: QUEUED
    }
}

data class MangaDownload(
    val id: String,
    val mangaId: String,
    val chapterId: String,
    val status: MangaDownloadStatus = MangaDownloadStatus.QUEUED,
    val totalPages: Int = 0,
    val downloadedPages: Int = 0,
    val queuedAt: Instant = Clock.System.now(),
    /** Why the download failed; null unless status is ERROR. */
    val error: String? = null,
)
