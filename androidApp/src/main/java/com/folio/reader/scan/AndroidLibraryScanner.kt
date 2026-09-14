package com.folio.reader.scan

import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.net.Uri
import android.os.Build
import android.os.Environment
import androidx.core.content.ContextCompat
import androidx.documentfile.provider.DocumentFile
import com.folio.reader.importer.LibraryScanCoordinator
import com.folio.reader.importer.LibraryScanSummary
import com.folio.reader.importer.MAX_SOURCE_BYTES
import com.folio.reader.importer.StagedScanFile
import com.folio.reader.importer.supportedScanExtensions
import java.io.File
import java.util.UUID
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

/**
 * Android side of library scanning, in two flavours:
 * - *Folder* scope: walks a SAF tree the user granted in the folder picker,
 *   staging supported files into bounded cache copies — identical trust level
 *   to the import picker.
 * - *Device* scope: walks the external storage roots directly through the file
 *   system. On Android 11+ that requires All Files Access (scoped storage hides
 *   other apps' non-media files — exactly EPUBs and PDFs — from every narrower
 *   grant), and on older versions the classic READ_EXTERNAL_STORAGE runtime
 *   permission, which is why [hasDeviceAccess] differs by SDK.
 */
object AndroidLibraryScanner {

    /** Upper bound on files per pass so an absurd tree cannot wedge the app. */
    private const val MAX_SCAN_FILES = 5_000

    /**
     * True when the whole-device scan can read shared storage. Android 11+
     * checks the All Files Access toggle; older versions check the runtime
     * READ_EXTERNAL_STORAGE grant.
     */
    fun hasDeviceAccess(context: Context): Boolean =
        if (Build.VERSION.SDK_INT >= 30) {
            Environment.isExternalStorageManager()
        } else {
            ContextCompat.checkSelfPermission(
                context,
                android.Manifest.permission.READ_EXTERNAL_STORAGE
            ) == PackageManager.PERMISSION_GRANTED
        }

    /** Intent that opens this app's page in the system All Files Access screen (Android 11+). */
    fun allFilesAccessIntent(context: Context): Intent? {
        if (Build.VERSION.SDK_INT < 30) return null
        val appSpecific = runCatching {
            Intent(
                android.provider.Settings.ACTION_MANAGE_APP_ALL_FILES_ACCESS_PERMISSION,
                Uri.parse("package:${context.packageName}")
            )
        }.getOrNull()
        return appSpecific ?: Intent(android.provider.Settings.ACTION_MANAGE_ALL_FILES_ACCESS_PERMISSION)
    }

    /** Storage roots visible to the app: the primary volume plus any SD card. */
    fun storageRoots(context: Context): List<File> {
        val roots = mutableListOf<File>()
        runCatching { Environment.getExternalStorageDirectory() }
            .getOrNull()
            ?.takeIf { it.isDirectory }
            ?.let { roots.add(it) }
        // Per-volume app directories look like /storage/<vol>/Android/data/<pkg>/files;
        // stripping the Android suffix yields each volume's root.
        context.getExternalFilesDirs(null).orEmpty().forEach { dir ->
            dir?.absolutePath
                ?.substringBefore("/Android/")
                ?.takeIf { it.isNotBlank() }
                ?.let(::File)
                ?.takeIf { it.isDirectory && roots.none { existing -> existing.absolutePath == it.absolutePath } }
                ?.let { roots.add(it) }
        }
        return roots
    }

    /**
     * Whole-device scan: direct filesystem walk over [storageRoots]. Reads the
     * primary volume and SD cards, skipping hidden folders and Android's
     * private tree (which also keeps the scan from re-importing Folio's own
     * library copies). Files pass to the coordinator by path, exactly like the
     * desktop scanner.
     */
    suspend fun scanStorage(
        context: Context,
        coordinator: LibraryScanCoordinator,
    ): LibraryScanSummary = withContext(Dispatchers.IO) {
        if (!hasDeviceAccess(context)) return@withContext LibraryScanSummary(0, 0, 0, 0, 0)
        val files = storageRoots(context)
            .asSequence()
            .flatMap { root ->
                root.walkTopDown()
                    .onEnter { dir ->
                        !dir.name.startsWith(".") &&
                            // Android's own tree at the volume root holds app-private
                            // sandboxes (including Folio's library copies).
                            !(dir.name.equals("Android", ignoreCase = true) && dir.parentFile == root)
                    }
                    .filter { it.isFile && it.extension.lowercase() in supportedScanExtensions }
            }
            .take(MAX_SCAN_FILES)
            .toList()
        val staged = files.map { StagedScanFile(path = it.absolutePath, filename = it.name) }
        coordinator.importStaged(staged)
    }

    /**
     * Grants persistable read access to [treeUri]; the settings screens call this
     * right after picking. Taking the grant can fail two ways: an outright
     * exception (some OEMs throw where stock Android does not), or a silent
     * no-op that leaves nothing persisted — so the result is verified against
     * the persisted grants list, not just the absence of a throw.
     */
    fun takePersistableGrant(context: Context, treeUri: Uri): Boolean = try {
        context.contentResolver.takePersistableUriPermission(
            treeUri,
            Intent.FLAG_GRANT_READ_URI_PERMISSION
        )
        context.contentResolver.persistedUriPermissions.any {
            it.uri == treeUri && it.isReadPermission
        }
    } catch (_: Exception) {
        false
    }

    /**
     * Short, human name for a granted tree, for the settings row. Roots are the
     * awkward case: `DocumentFile.name` is often null for a storage root and the
     * tree URI's last segment is just the volume id, so fall back through the
     * volume id ("primary" → the device's main storage) before giving up.
     */
    fun describeTree(context: Context, treeUri: Uri): String? {
        val name = runCatching { DocumentFile.fromTreeUri(context, treeUri)?.name }
            .getOrNull()
            ?.takeIf { it.isNotBlank() }
        if (name != null) return name
        // tree/primary: → "primary"; tree/1234-ABCD: → "1234-ABCD"
        val volume = treeUri.pathSegments.getOrNull(1)
            ?.trimEnd(':')
            ?.takeIf { it.isNotBlank() }
        return when {
            volume == null -> null
            volume.equals("primary", ignoreCase = true) -> "Device storage"
            else -> volume
        }
    }

    /**
     * Where the tree picker should open: the primary external storage root. The
     * "whole device" scope is only as wide as the grant, so the picker has to
     * land where the user can actually select the storage root — without this
     * hint it opens at an arbitrary recent location and roots are easy to miss
     * (and some OEM UIs hide them further down the drawer).
     */
    fun storageRootHintUri(): Uri? = runCatching {
        android.provider.DocumentsContract.buildDocumentUri(
            "com.android.externalstorage.documents",
            "primary:"
        )
    }.getOrNull()

    suspend fun scan(
        context: Context,
        treeUri: Uri,
        coordinator: LibraryScanCoordinator,
    ): LibraryScanSummary = withContext(Dispatchers.IO) {
        val root = DocumentFile.fromTreeUri(context, treeUri)
            ?: return@withContext LibraryScanSummary(0, 0, 0, 0, 0)
        val staged = mutableListOf<StagedScanFile>()
        val temps = mutableListOf<File>()
        try {
            // Breadth-first so a folder picked for its immediate contents shows
            // results even when deep subtrees are still walking.
            val queue = ArrayDeque<DocumentFile>()
            queue.add(root)
            while (queue.isNotEmpty()) {
                val dir = queue.removeFirst()
                val entries = runCatching { dir.listFiles() }.getOrNull() ?: continue
                for (entry in entries) {
                    val name = entry.name ?: continue
                    if (name.startsWith(".")) continue
                    if (entry.isDirectory) {
                        // Android's own tree holds app-private sandboxes that SAF
                        // cannot enter anyway; skipping it keeps the walk quiet.
                        if (name.equals("Android", ignoreCase = true) && dir.uri == root.uri) continue
                        queue.add(entry)
                    } else if (entry.isFile &&
                        name.substringAfterLast('.', "").lowercase() in supportedScanExtensions
                    ) {
                        stage(context, entry, name)?.let { file ->
                            temps += File(file.path)
                            staged += file
                        }
                    }
                }
            }
            coordinator.importStaged(staged)
        } finally {
            temps.forEach { runCatching { it.delete() } }
        }
    }

    private fun stage(context: Context, file: DocumentFile, name: String): StagedScanFile? {
        val extension = name.substringAfterLast('.', "")
            .lowercase()
            .takeIf { it.matches(Regex("[a-z0-9]{1,8}")) }
        val temp = File(
            context.cacheDir,
            "scan_${UUID.randomUUID()}${extension?.let { ".$it" }.orEmpty()}"
        )
        return try {
            context.contentResolver.openInputStream(file.uri)?.use { input ->
                temp.outputStream().use { output ->
                    val buffer = ByteArray(64 * 1024)
                    var total = 0L
                    while (true) {
                        val count = input.read(buffer)
                        if (count < 0) break
                        total += count
                        if (total > MAX_SOURCE_BYTES) {
                            throw IllegalArgumentException("$name exceeds the scan size limit")
                        }
                        output.write(buffer, 0, count)
                    }
                }
            } ?: return null
            StagedScanFile(temp.absolutePath, name, file.type)
        } catch (_: Exception) {
            runCatching { temp.delete() }
            null
        }
    }
}
