package com.folio.reader.importer

/**
 * Library scanning: find ebook/document files outside the app and import them.
 *
 * The scan is split the same way on both platforms: a platform layer produces
 * staged copies of candidate files (Android walks a SAF tree the user granted;
 * desktop walks a chosen folder or the user's common folders), and this
 * coordinator runs them through the normal [IncomingContentCoordinator] — so a
 * scanned file is detected, size-checked, deduplicated and indexed exactly like
 * one picked by hand. Re-scanning is safe: already-imported files come back as
 * duplicates and change nothing.
 */
object LibraryScanKeys {
    const val SCOPE = "library.scan.scope"
    const val FOLDER = "library.scan.folder"
    const val DEVICE = "library.scan.device"
    const val ON_START = "library.scan.on_start"
    const val LAST = "library.scan.last"
}

enum class LibraryScanScope {
    OFF,
    FOLDER,
    /** Android: a SAF tree over the storage root the user granted. Desktop: the user's common folders. */
    DEVICE;

    companion object {
        fun fromRaw(raw: String?): LibraryScanScope =
            entries.firstOrNull { it.name == raw } ?: OFF
    }
}

/** Extensions the import pipeline accepts; platform scanners filter their walks on this set. */
val supportedScanExtensions: Set<String> =
    IncomingFormat.entries.map { it.canonicalExtension }.toSet() + "htm"

/** One staged candidate: a local temp copy of a discovered file, ready for import. */
data class StagedScanFile(
    val path: String,
    val filename: String,
    val mimeType: String? = null,
)

data class LibraryScanSummary(
    val scanned: Int,
    val imported: Int,
    val duplicates: Int,
    val skipped: Int,
    val failed: Int,
) {
    /** One line for settings screens and the stored "last scan" note. */
    fun describe(): String {
        if (scanned == 0) return "No importable files found"
        val parts = buildList {
            if (imported > 0) add("$imported imported")
            if (duplicates > 0) add("$duplicates already in the library")
            if (skipped > 0) add("$skipped skipped")
            if (failed > 0) add("$failed failed")
        }
        return when {
            parts.isEmpty() -> "No importable files found"
            else -> parts.joinToString(" · ")
        }
    }
}

/** Feeds staged candidates through the shared import pipeline and tallies the outcome. */
class LibraryScanCoordinator(
    private val incoming: IncomingContentCoordinator,
) {
    suspend fun importStaged(files: List<StagedScanFile>): LibraryScanSummary {
        if (files.isEmpty()) return LibraryScanSummary(0, 0, 0, 0, 0)
        val results = incoming.importMany(
            files.map { IncomingContent(path = it.path, filename = it.filename, mimeType = it.mimeType) }
        )
        var imported = 0
        var duplicates = 0
        var skipped = 0
        var failed = 0
        results.forEach { result ->
            when (result) {
                is IncomingContentResult.ImportedBook,
                is IncomingContentResult.ImportedDocument -> imported++

                is IncomingContentResult.DuplicateBook,
                is IncomingContentResult.DuplicateDocument -> duplicates++

                is IncomingContentResult.Unsupported,
                is IncomingContentResult.Unsafe -> skipped++

                else -> failed++
            }
        }
        return LibraryScanSummary(
            scanned = files.size,
            imported = imported,
            duplicates = duplicates,
            skipped = skipped,
            failed = failed,
        )
    }
}
