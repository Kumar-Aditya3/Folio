package com.folio.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.compose.ui.platform.LocalLifecycleOwner
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.LifecycleEventObserver
import androidx.work.WorkInfo
import com.folio.reader.ml.EmbeddingModelCatalog
import com.folio.reader.ml.ModelDownloadState
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.SemanticSearchPanelState
import com.folio.reader.ui.settings.SemanticSearchSettingsPanel
import com.folio.reader.work.EmbeddingBackfillScheduler
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale

/**
 * Semantic search settings host (ML_PLAN Phase 4).
 *
 * Owns nothing itself: the model lives on disk under `getModelsDir()`, the vectors live in the
 * database, and the backfill lives in WorkManager. This screen is a view onto those three, which is
 * why it survives being closed mid-download or mid-index.
 *
 * The one thing it does own is *which* model is in force. It reads that from
 * `graph.modelSelection` rather than from a constant, so a switch propagates to the indexer,
 * the searcher and the tagger together.
 */
@Composable
fun SettingsSemanticSearchScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = navModel.graph
    val appScope = navModel.activity.appScope
    val selection = graph.modelSelection
    val model by selection.model.collectAsState()
    var error by remember { mutableStateOf<String?>(null) }

    val downloadState by graph.modelDownloader.state(model).collectAsState()
    val progress by graph.chunkRepository.observeProgress(model.id).collectAsState(initial = null)
    val workInfos by EmbeddingBackfillScheduler.observe(context).collectAsState(emptyList())

    val downloadFailure = (downloadState as? ModelDownloadState.Failed)?.message

    // The index follows the selected model, so "has anything been indexed?" is a per-model
    // question. Read here rather than derived from `progress`, which is 0 for both "not indexed
    // yet" and "not indexed *for this model*" and cannot tell the reader which they are looking at.
    var hasVectorsForModel by remember(model) { mutableStateOf(false) }
    LaunchedEffect(model) {
        hasVectorsForModel = withContext(Dispatchers.IO) {
            runCatching { graph.chunkRepository.chunkCount(model.id) > 0 }.getOrDefault(false)
        }
    }

    // Whether the OS lets this app run in the background — see [BackgroundIndexingPermission] for
    // why the answer decides whether the index can be built at all on this device.
    //
    // Read on resume rather than once, because the only way it changes is the reader leaving to
    // grant it in system settings and coming back. Same shape as the All Files Access check in
    // [SettingsLibraryScanScreen]: the state is only knowable after resume, so watch the lifecycle
    // instead of the picker.
    var batteryExempt by remember { mutableStateOf(BackgroundIndexingPermission.isExempt(context)) }
    val lifecycleOwner = LocalLifecycleOwner.current
    DisposableEffect(lifecycleOwner) {
        val observer = LifecycleEventObserver { _, event ->
            if (event == Lifecycle.Event.ON_RESUME) {
                batteryExempt = BackgroundIndexingPermission.isExempt(context)
            }
        }
        lifecycleOwner.lifecycle.addObserver(observer)
        onDispose { lifecycleOwner.lifecycle.removeObserver(observer) }
    }

    SettingsCategoryScaffold(title = "Semantic search", onBack = onBack) {
        SemanticSearchSettingsPanel(
            state = SemanticSearchPanelState(
                modelName = model.displayName,
                modelSizeLabel = formatBytes(model.sizeBytes),
                installed = downloadState is ModelDownloadState.Installed,
                downloadFraction = when (val state = downloadState) {
                    is ModelDownloadState.Downloading -> state.fraction
                    is ModelDownloadState.Verifying -> 1f
                    else -> null
                },
                downloadLabel = when (val state = downloadState) {
                    is ModelDownloadState.Downloading ->
                        "Downloading… ${(state.fraction * 100).toInt()}%"
                    is ModelDownloadState.Verifying -> "Verifying checksum…"
                    else -> null
                },
                // `!isFinished` was wrong: a work request parked in retry backoff is ENQUEUED,
                // which is not finished, so the panel claimed to be indexing forever and the
                // button stayed disabled — the reader could never push the index along. Only a
                // worker that is actually executing counts as indexing.
                isIndexing = workInfos.any { it.state == WorkInfo.State.RUNNING },
                isQueued = workInfos.any { it.state == WorkInfo.State.ENQUEUED },
                indexedChapters = progress?.indexedChapters ?: 0,
                totalChapters = progress?.totalChapters ?: 0,
                indexedChunks = progress?.indexedChunks ?: 0,
                error = error ?: downloadFailure,
                choices = EmbeddingModelCatalog.selectable.map { it.id to it.displayName },
                selectedModelId = model.id,
                selectionNeedsIndex = !hasVectorsForModel,
                batteryExempt = batteryExempt,
            ),
            onDownload = {
                error = null
                appScope.launch {
                    // Work off the main thread, but write the result back on the scope's own
                    // dispatcher: Compose state is not thread-safe to write from IO.
                    val result = withContext(Dispatchers.IO) { graph.modelDownloader.ensureModel(model) }
                    result.onFailure { error = it.message ?: "The model could not be downloaded." }
                }
            },
            onDeleteModel = {
                error = null
                appScope.launch {
                    val result = withContext(Dispatchers.IO) {
                        runCatching { graph.modelDownloader.deleteModel(model) }
                    }
                    result.onFailure { error = it.message ?: "The model could not be removed." }
                }
            },
            onBuildIndex = {
                error = null
                EmbeddingBackfillScheduler.runNow(context)
            },
            onAllowBackgroundIndexing = {
                // Deliberately does not assume the grant. The reader may decline, or grant it and
                // come back through a different route; the lifecycle observer above re-reads the
                // real answer on resume, so this only has to open the dialog.
                BackgroundIndexingPermission.request(context)
            },
            onSelectModel = { id ->
                error = null
                val chosen = EmbeddingModelCatalog.byId(id)
                if (chosen != null && chosen.id != model.id) {
                    appScope.launch {
                        withContext(Dispatchers.IO) {
                            // Persist first, adopt second. The reverse order would leave a crash
                            // between the two showing a model that is not the one stored, and the
                            // next launch silently reverting — the reader's choice is the durable
                            // fact, so it is written before anything depends on it.
                            runCatching { selection.persist(chosen) }
                                .onFailure { error = it.message ?: "The choice could not be saved." }
                            selection.adopt(chosen)
                        }
                    }
                }
            },
        )

        // Atlas & Echoes master switch. Off hides both discovery surfaces (the Home Atlas hero and
        // the reader's Echoes action) without touching the index — a preference, not a teardown.
        Row(
            modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 16.dp),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Semantic discovery (Atlas & Echoes)", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Map your library by meaning and surface resonant passages across books, entirely on this device.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                )
            }
            Switch(
                checked = navModel.globalSettings.semanticDiscovery,
                onCheckedChange = { navModel.updateSettings(navModel.globalSettings.copy(semanticDiscovery = it)) },
            )
        }
    }
}

private fun formatBytes(bytes: Long): String = when {
    bytes >= 1_000_000_000L -> String.format(Locale.US, "%.1f GB", bytes / 1_000_000_000.0)
    bytes >= 1_000_000L -> String.format(Locale.US, "%.0f MB", bytes / 1_000_000.0)
    bytes >= 1_000L -> String.format(Locale.US, "%.0f KB", bytes / 1_000.0)
    else -> "$bytes B"
}
