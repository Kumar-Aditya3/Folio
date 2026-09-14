package com.folio.reader

import com.folio.reader.importer.LibraryScanCoordinator
import com.folio.reader.importer.LibraryScanKeys
import com.folio.reader.importer.LibraryScanScope
import com.folio.reader.importer.LibraryScanSummary
import com.folio.reader.importer.supportedScanExtensions
import java.io.File
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Desktop side of library scanning: walks a chosen folder, or — for the device
 * scope — the user's common folders (Desktop, Documents, Downloads), since
 * walking whole drives would take longer than anyone is willing to wait.
 * Discovered files go straight into the shared import pipeline, which copies
 * them into the library and deduplicates by hash.
 */
object DesktopLibraryScanner {

    /** Upper bound on files per pass so an absurd tree cannot wedge the app. */
    private const val MAX_SCAN_FILES = 2_000

    suspend fun scan(
        coordinator: LibraryScanCoordinator,
        settingsRepository: com.folio.reader.database.SettingsRepository,
    ): LibraryScanSummary? = withContext(Dispatchers.IO) {
        val scope = LibraryScanScope.fromRaw(
            runCatching { settingsRepository.getRaw(LibraryScanKeys.SCOPE) }.getOrNull()
        )
        when (scope) {
            LibraryScanScope.OFF -> null
            LibraryScanScope.FOLDER -> {
                val path = runCatching { settingsRepository.getRaw(LibraryScanKeys.FOLDER) }.getOrNull()
                val dir = path?.takeIf { it.isNotBlank() }?.let(::File)?.takeIf { it.isDirectory }
                if (dir == null) null else scanFiles(coordinator, settingsRepository, walk(listOf(dir)))
            }

            LibraryScanScope.DEVICE -> {
                val home = File(System.getProperty("user.home"))
                val roots = listOf("Desktop", "Documents", "Downloads")
                    .map { File(home, it) }
                    .filter { it.isDirectory }
                if (roots.isEmpty()) null else scanFiles(coordinator, settingsRepository, walk(roots))
            }
        }
    }

    private fun walk(roots: List<File>): List<File> = roots
        .asSequence()
        .flatMap { root ->
            root.walkTopDown()
                // Hidden folders hold app state, not reading material.
                .onEnter { !it.name.startsWith(".") }
                .filter { it.isFile && it.extension.lowercase() in supportedScanExtensions }
        }
        .take(MAX_SCAN_FILES)
        .toList()

    private suspend fun scanFiles(
        coordinator: LibraryScanCoordinator,
        settingsRepository: com.folio.reader.database.SettingsRepository,
        files: List<File>,
    ): LibraryScanSummary {
        val staged = files.map { file ->
            com.folio.reader.importer.StagedScanFile(
                path = file.absolutePath,
                filename = file.name,
            )
        }
        val summary = coordinator.importStaged(staged)
        runCatching { settingsRepository.setRaw(LibraryScanKeys.LAST, summary.describe()) }
        return summary
    }
}
