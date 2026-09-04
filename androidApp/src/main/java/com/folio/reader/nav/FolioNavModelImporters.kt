package com.folio.reader.nav

import android.content.Intent
import android.net.Uri
import android.provider.OpenableColumns
import com.folio.reader.MainActivity
import com.folio.reader.export.RestoreMode
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

/**
 * Document-picker handlers that back [FolioNavCallbacks]. Kept off MainActivity
 * so the activity stays under the §6 line budget; they operate on the nav
 * model's hoisted state (settings snapshot, annotation format, backup manager).
 */

internal fun FolioNavModelImpl.handleFontImport(uri: Uri) {
    val activity = activity
    val graph = graph
    activity.appScope.launch(Dispatchers.IO) {
        val displayName = runCatching {
            activity.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
                ?.use { c -> if (c.moveToFirst()) c.getString(0) else null }
        }.getOrNull() ?: "imported_font.ttf"
        val tempFile = File(activity.cacheDir, displayName)
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            graph.fontManager.run {
                runCatching { importFont(tempFile, tempFile.nameWithoutExtension) }
                    .onSuccess { font ->
                        if (font != null) {
                            runCatching {
                                val settings = graph.settingsRepository.getGlobalSettings()
                                graph.settingsRepository.saveGlobalSettings(
                                    settings.copy(customFonts = settings.customFonts + font)
                                )
                            }.onFailure { e ->
                                activity.appScope.launch(Dispatchers.Main) {
                                    activity.importStatus = "Font save failed: ${e.message}"
                                }
                                return@onSuccess
                            }
                            activity.appScope.launch(Dispatchers.Main) {
                                globalSettings = globalSettings.copy(
                                    customFonts = globalSettings.customFonts + font
                                )
                                activity.importStatus = "Font imported: ${font.name}"
                            }
                        } else {
                            activity.appScope.launch(Dispatchers.Main) {
                                activity.importStatus = "Failed: unsupported font file"
                            }
                        }
                    }
                    .onFailure { e ->
                        activity.appScope.launch(Dispatchers.Main) {
                            activity.importStatus = "Font import failed: ${e.message}"
                        }
                    }
            }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Font import failed: ${e.message}" }
        } finally {
            tempFile.delete()
        }
    }
}

internal fun FolioNavModelImpl.handleMangaBackupImport(uri: Uri) {
    val activity = activity
    activity.appScope.launch(Dispatchers.IO) {
        val tmp = File(activity.cacheDir, "manga_backup_${System.currentTimeMillis()}.backup")
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                tmp.outputStream().use { output -> input.copyTo(output) }
            }
            val result = mangaBackupManager.importFromMihonBackup(tmp)
            activity.appScope.launch(Dispatchers.Main) {
                activity.importStatus = "Imported ${result.manga} manga, ${result.chapters} chapters"
                activity.refreshTick++
            }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Backup import failed: ${e.message}" }
        } finally {
            tmp.delete()
        }
    }
}

internal fun FolioNavModelImpl.handleMangaBackupExport(uri: Uri) {
    val activity = activity
    activity.appScope.launch(Dispatchers.IO) {
        val tmp = File(activity.cacheDir, "folio_manga.backup")
        try {
            val count = mangaBackupManager.exportToMihonBackup(tmp)
            activity.contentResolver.openOutputStream(uri)?.use { output ->
                tmp.inputStream().use { input -> input.copyTo(output) }
            }
            activity.appScope.launch(Dispatchers.Main) {
                activity.importStatus = "Exported $count manga to backup"
            }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Backup export failed: ${e.message}" }
        } finally {
            tmp.delete()
        }
    }
}

internal fun FolioNavModelImpl.handleFullBackupExport(uri: Uri) {
    val activity = activity
    val graph = graph
    activity.appScope.launch(Dispatchers.IO) {
        val tempFile = File(activity.cacheDir, "folio-backup-${System.currentTimeMillis()}.json")
        try {
            graph.exportManager.createFullBackup(tempFile, graph.deviceId)
                .onSuccess { backup ->
                    activity.contentResolver.openOutputStream(uri)?.use { output ->
                        tempFile.inputStream().use { input -> input.copyTo(output) }
                    }
                    activity.appScope.launch(Dispatchers.Main) {
                        activity.importStatus =
                            "Backup saved: ${backup.books.size} books, ${backup.highlights.size} highlights"
                    }
                }
                .onFailure { e ->
                    activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Backup failed: ${e.message}" }
                }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Backup failed: ${e.message}" }
        } finally {
            tempFile.delete()
        }
    }
}

internal fun FolioNavModelImpl.handleBackupRestore(uri: Uri) {
    val activity = activity
    val graph = graph
    activity.appScope.launch(Dispatchers.IO) {
        val tempFile = File(activity.cacheDir, "restore_${System.currentTimeMillis()}.json")
        try {
            activity.contentResolver.openInputStream(uri)?.use { input ->
                tempFile.outputStream().use { output -> input.copyTo(output) }
            }
            graph.exportManager.restoreBackup(tempFile, RestoreMode.MERGE)
                .onSuccess { summary ->
                    activity.appScope.launch(Dispatchers.Main) {
                        activity.importStatus =
                            "Restored: ${summary.booksRestored} books, ${summary.highlightsRestored} highlights, ${summary.errors.size} errors"
                        activity.refreshTick++
                    }
                }
                .onFailure { e ->
                    activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Restore failed: ${e.message}" }
                }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Restore failed: ${e.message}" }
        } finally {
            tempFile.delete()
        }
    }
}

internal fun FolioNavModelImpl.handleAnnotationsExport(uri: Uri) {
    val activity = activity
    val graph = graph
    val format = annotationFormat
    activity.appScope.launch(Dispatchers.IO) {
        val tempFile = File(activity.cacheDir, "annotations-${System.currentTimeMillis()}.$format")
        try {
            when (format) {
                "markdown" -> graph.exportManager.exportAnnotationsMarkdown(tempFile, null)
                "csv" -> graph.exportManager.exportAnnotationsCsv(tempFile, null)
                else -> graph.exportManager.exportAnnotationsJson(tempFile, null)
            }.onSuccess {
                activity.contentResolver.openOutputStream(uri)?.use { output ->
                    tempFile.inputStream().use { input -> input.copyTo(output) }
                }
                activity.appScope.launch(Dispatchers.Main) {
                    activity.importStatus = "Annotations exported to ${uri.lastPathSegment}"
                }
            }.onFailure { e ->
                activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Export failed: ${e.message}" }
            }
        } catch (e: Exception) {
            activity.appScope.launch(Dispatchers.Main) { activity.importStatus = "Export failed: ${e.message}" }
        } finally {
            tempFile.delete()
        }
    }
}

/**
 * Points manga downloads at a user-picked SAF folder. Takes a persistable
 * permission, remembers the choice, migrates existing chapters over, and keeps
 * the old location readable until migration finishes.
 */
internal fun MainActivity.changeMangaDownloadsLocation(treeUri: Uri, onDone: () -> Unit = {}) {
    val graph = (application as com.folio.reader.FolioApplication).graph
    try {
        contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        )
    } catch (e: SecurityException) {
        importStatus = "Folio needs read/write access to that folder"
        return
    }
    appScope.launch(Dispatchers.IO) {
        runCatching {
            val storage = com.folio.reader.manga.SafDownloadStorage(applicationContext, treeUri)
            val ok = graph.mangaDownloadManager.switchStorage(storage) { done, total ->
                withContext(Dispatchers.Main) { importStatus = "Moving downloads... $done/$total" }
            }
            if (!ok) {
                withContext(Dispatchers.Main) {
                    importStatus = "Couldn't use that folder for downloads; keeping the current location"
                }
                return@runCatching
            }
            graph.settingsRepository.setRaw(com.folio.reader.manga.KEY_MANGA_DOWNLOADS_LOCATION, treeUri.toString())
            withContext(Dispatchers.Main) {
                importStatus = "Downloads now save to '${storage.describe()}'"
                onDone()
            }
        }.onFailure { e ->
            withContext(Dispatchers.Main) { importStatus = "Could not use that folder: ${e.message}" }
        }
    }
}
