package com.folio.reader.settings

import android.app.usage.StorageStatsManager
import android.content.Context
import android.os.Build
import android.os.storage.StorageManager
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.folio.reader.ml.EmbeddingModelCatalog
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.settings.StorageUsagePanel
import com.folio.reader.ui.settings.StorageUsageSlice
import com.folio.reader.ui.settings.StorageOtherModel
import com.folio.reader.ui.settings.StorageOtherModelsCard
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * Storage screen: a donut of where the app's on-disk space goes, by category, plus a card that
 * clears the vector index of models the reader has switched away from.
 *
 * Owns nothing durable — it reads [com.folio.reader.platform.FolioFileSystem.storageBreakdown] and
 * the OS-reported app/code size off the main thread and hands them to the shared panel. The
 * "Database & index" slice covers the semantic vector index: the vectors are BLOB rows inside
 * `folio.db`, so a filesystem walk cannot split them out.
 *
 * The app/code slice comes from [StorageStatsManager], not the filesystem walk, because the APK and
 * extracted native libraries are the OS's accounting, not files under the app's data dir — and they
 * are usually the difference between "my walk says 2 GB" and "Settings says 11 GB".
 */
@Composable
fun SettingsStorageScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val context = LocalContext.current
    val graph = navModel.graph
    val appScope = navModel.activity.appScope
    val selection = graph.modelSelection
    val currentModel by selection.model.collectAsState()

    var slices by remember { mutableStateOf<List<StorageUsageSlice>?>(null) }
    var otherModels by remember { mutableStateOf<List<StorageOtherModel>>(emptyList()) }
    var reloadKey by remember { mutableStateOf(0) }
    var clearing by remember { mutableStateOf(false) }

    LaunchedEffect(reloadKey, currentModel.id) {
        val loaded = withContext(Dispatchers.IO) {
            val categories = graph.platform.fileSystem.storageBreakdown()
                .map { StorageUsageSlice(it.label, it.bytes) }
            val appBytes = appAndCodeBytes(context)
            val withApp =
                if (appBytes > 0L) categories + StorageUsageSlice("App & code", appBytes) else categories

            // Models the reader is no longer using but whose vectors still occupy the database.
            val counts = runCatching { graph.chunkRepository.modelChunkCounts() }.getOrDefault(emptyMap())
            val others = counts
                .filterKeys { it != currentModel.id }
                .map { (id, n) ->
                    StorageOtherModel(
                        modelId = id,
                        label = EmbeddingModelCatalog.byId(id)?.displayName ?: id,
                        chunkCount = n,
                    )
                }
                .sortedByDescending { it.chunkCount }
            withApp to others
        }
        slices = loaded.first
        otherModels = loaded.second
    }

    SettingsCategoryScaffold(title = "Storage", onBack = onBack) {
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceMovement),
        ) {
            when (val current = slices) {
                null -> Text(
                    "Calculating…",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
                else -> StorageUsagePanel(current)
            }

            if (otherModels.isNotEmpty()) {
                StorageOtherModelsCard(
                    models = otherModels,
                    busy = clearing,
                    onClear = {
                        if (clearing) return@StorageOtherModelsCard
                        clearing = true
                        appScope.launch {
                            withContext(Dispatchers.IO) {
                                otherModels.forEach { m ->
                                    runCatching { graph.chunkRepository.deleteChunksForModel(m.modelId) }
                                }
                            }
                            clearing = false
                            reloadKey++
                        }
                    },
                )
            }
        }
    }
}

/**
 * APK + extracted native libs + app-data bytes as the OS accounts them, or 0 when unavailable.
 *
 * `StorageStatsManager` is API 26+; below that there is no supported total, so the slice is simply
 * omitted rather than guessed. `appBytes` is the code (APK + libs); `dataBytes`/`cacheBytes` overlap
 * with the filesystem walk, so only `appBytes` is added as a distinct slice to avoid double-counting.
 */
private fun appAndCodeBytes(context: Context): Long {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return 0L
    return runCatching {
        val stats = context.getSystemService(Context.STORAGE_STATS_SERVICE) as StorageStatsManager
        val uuid = StorageManager.UUID_DEFAULT
        stats.queryStatsForPackage(uuid, context.packageName, android.os.Process.myUserHandle()).appBytes
    }.getOrDefault(0L)
}
