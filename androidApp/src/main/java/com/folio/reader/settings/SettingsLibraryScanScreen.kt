package com.folio.reader.settings

import android.Manifest
import android.net.Uri
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.layout.Column
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import com.folio.reader.importer.LibraryScanKeys
import com.folio.reader.importer.LibraryScanScope
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.scan.AndroidLibraryScanner
import com.folio.reader.ui.settings.LibraryScanPanelState
import com.folio.reader.ui.settings.LibraryScanSettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Library scanning settings: pick a scope — off, one folder, or the whole
 * device — optionally re-scan on app start, and run a scan on demand.
 *
 * Folder scope uses a SAF tree pick with a persistable read grant. Device scope
 * is a real permission flow, which is what "whole device" has to mean on modern
 * Android: scoped storage hides other apps' non-media files (EPUBs, PDFs) from
 * every narrower grant, so Android 11+ asks for All Files Access — an in-app
 * rationale first, then the system toggle — and older versions show the classic
 * storage permission dialog. No folder picker is involved either way.
 */
@Composable
fun SettingsLibraryScanScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = navModel.graph
    val appScope = navModel.activity.appScope
    var state by remember { mutableStateOf(LibraryScanPanelState()) }
    var showAllFilesRationale by remember { mutableStateOf(false) }

    fun refreshDeviceAccess() {
        val granted = AndroidLibraryScanner.hasDeviceAccess(context)
        state = state.copy(
            deviceRootDescription = if (granted) "All files on this device" else null,
        )
    }

    LaunchedEffect(Unit) {
        val repo = graph.settingsRepository
        val raw = runCatching { repo.getRaw(LibraryScanKeys.FOLDER) }.getOrNull()
            ?.takeIf { it.isNotBlank() }
        val folderDescription = raw
            ?.let { runCatching { Uri.parse(it) }.getOrNull() }
            ?.let { AndroidLibraryScanner.describeTree(context, it) }
        state = LibraryScanPanelState(
            scope = LibraryScanScope.fromRaw(
                runCatching { repo.getRaw(LibraryScanKeys.SCOPE) }.getOrNull()
            ),
            folderDescription = folderDescription,
            scanOnStart = runCatching { repo.getRaw(LibraryScanKeys.ON_START) }.getOrNull() == "1",
            lastScanSummary = runCatching { repo.getRaw(LibraryScanKeys.LAST) }.getOrNull(),
            deviceRootDescription =
                if (AndroidLibraryScanner.hasDeviceAccess(context)) "All files on this device" else null,
        )
    }

    // Returning from the system All Files Access screen: the toggle state is
    // only knowable after resume, so watch the lifecycle instead of the picker.
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) refreshDeviceAccess()
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    fun storeTree(uri: Uri) {
        if (!AndroidLibraryScanner.takePersistableGrant(context, uri)) {
            // A pick the system refused to persist (some OEMs block whole
            // subtrees) must not read as success.
            state = state.copy(
                accessError = "Couldn't get access to that folder — the device refused the grant. " +
                    "Try another folder, or use the whole-device scope instead."
            )
            return
        }
        state = state.copy(
            accessError = null,
            folderDescription = AndroidLibraryScanner.describeTree(context, uri),
        )
        appScope.launch(Dispatchers.IO) {
            runCatching { graph.settingsRepository.setRaw(LibraryScanKeys.FOLDER, uri.toString()) }
        }
    }

    fun requestDeviceAccess() {
        if (AndroidLibraryScanner.hasDeviceAccess(context)) {
            refreshDeviceAccess()
            return
        }
        if (Build.VERSION.SDK_INT >= 30) {
            // All Files Access is granted in system settings, not a dialog; the
            // rationale explains why before handing the user over.
            showAllFilesRationale = true
        }
        // Below Android 11 the runtime dialog below is the whole flow; it is
        // launched by the permission launcher declared after this function.
    }

    val folderPicker = rememberLauncherForActivityResult(
        ActivityResultContracts.OpenDocumentTree()
    ) { uri -> if (uri != null) storeTree(uri) }

    val storagePermissionLauncher = rememberLauncherForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) {
            refreshDeviceAccess()
        } else {
            state = state.copy(
                accessError = "Storage permission was denied — whole-device scanning stays off until it is granted."
            )
        }
    }

    if (showAllFilesRationale) {
        AlertDialog(
            onDismissRequest = { showAllFilesRationale = false },
            title = { Text("Allow access to all files?") },
            text = {
                Text(
                    "To find books and documents anywhere on the device, Android requires " +
                        "Folio to be granted all files access. Without it, the system hides " +
                        "files that belong to other apps from every narrower permission."
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    showAllFilesRationale = false
                    runCatching {
                        AndroidLibraryScanner.allFilesAccessIntent(context)
                            ?.let { context.startActivity(it) }
                    }
                }) { Text("Open settings") }
            },
            dismissButton = {
                TextButton(onClick = { showAllFilesRationale = false }) { Text("Not now") }
            }
        )
    }

    SettingsCategoryScaffold(title = "Library scanning", onBack = onBack) {
        Column {
            LibraryScanSettingsPanel(
                state = state,
                onScopeChange = { next ->
                    state = state.copy(scope = next)
                    appScope.launch(Dispatchers.IO) {
                        runCatching { graph.settingsRepository.setRaw(LibraryScanKeys.SCOPE, next.name) }
                    }
                },
                onPickFolder = {
                    // Opens at the primary storage root so common folders are
                    // one tap away; anything under it can be picked.
                    folderPicker.launch(AndroidLibraryScanner.storageRootHintUri())
                },
                onPickDeviceRoot = {
                    if (Build.VERSION.SDK_INT < 30) {
                        storagePermissionLauncher.launch(Manifest.permission.READ_EXTERNAL_STORAGE)
                    } else {
                        requestDeviceAccess()
                    }
                },
                deviceEmptyLabel = "Not granted yet",
                deviceGrantLabel = "Grant",
                onScanOnStartChange = { enabled ->
                    state = state.copy(scanOnStart = enabled)
                    appScope.launch(Dispatchers.IO) {
                        runCatching {
                            graph.settingsRepository.setRaw(LibraryScanKeys.ON_START, if (enabled) "1" else "")
                        }
                    }
                },
                onScanNow = {
                    if (state.scope == LibraryScanScope.DEVICE && !AndroidLibraryScanner.hasDeviceAccess(context)) {
                        // Gating here keeps the button honest: a device scan
                        // without access is a silent no-op, which reads as
                        // "scanning is broken".
                        state = state.copy(
                            isScanning = false,
                            lastScanSummary = "Grant device access first, then scan."
                        )
                        return@LibraryScanSettingsPanel
                    }
                    state = state.copy(isScanning = true)
                    appScope.launch(Dispatchers.IO) {
                        val summary = runCatching { graph.runLibraryScan() }.getOrNull()
                        withContext(Dispatchers.Main) {
                            state = state.copy(
                                isScanning = false,
                                lastScanSummary = summary?.describe()
                                    ?: "Nothing to scan — choose a folder or grant device access first"
                            )
                        }
                    }
                },
            )
        }
    }
}
