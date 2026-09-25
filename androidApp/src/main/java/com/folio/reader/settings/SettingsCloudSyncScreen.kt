package com.folio.reader.settings

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.settings.CloudSyncSettingsPanel
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext

@Composable
fun SettingsCloudSyncScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val graph = navModel.graph
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = graph.settingsRepository.getGlobalSettings() }
        // Credentials come from the no-backup SecureCredentialStore, not the settings blob.
        runCatching {
            navModel.syncCredentials = withContext(Dispatchers.IO) { graph.secureCredentialStore.load() }
        }
    }
    val syncState = navModel.collectSyncState()
        ?: SyncState(isConfigured = graph.isSyncConfigured)
    SettingsCategoryScaffold(title = "Cloud sync", onBack = onBack) {
        CloudSyncSettingsPanel(
            settings = navModel.globalSettings,
            syncState = syncState,
            onSettingsChange = { navModel.updateSettings(it) },
            onSyncNow = { graph.syncEngine?.triggerSync(immediate = true) },
            credentials = navModel.syncCredentials,
            onCredentialsChange = { navModel.saveSyncCredentials(it) }
        )
    }
}