package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.input.PasswordVisualTransformation
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.folioPanel

@Composable
fun CloudSyncSettingsPanel(
    settings: ReaderSettings,
    syncState: com.folio.reader.sync.SyncState,
    onSettingsChange: (ReaderSettings) -> Unit,
    onSyncNow: () -> Unit
) {
    var apiKey by remember(settings.firebaseApiKey) { mutableStateOf(settings.firebaseApiKey) }
    var projectId by remember(settings.firebaseProjectId) { mutableStateOf(settings.firebaseProjectId) }
    var accountEmail by remember(settings.syncAccountEmail) { mutableStateOf(settings.syncAccountEmail) }
    var accountPassword by remember(settings.syncAccountPassword) { mutableStateOf(settings.syncAccountPassword) }
    val isConnected = settings.firebaseApiKey.isNotBlank() && settings.firebaseProjectId.isNotBlank()

    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Real-time sync status card
        com.folio.reader.ui.components.SyncStatusCard(
            syncState = syncState,
            isConnected = isConnected,
            onSyncNow = onSyncNow
        )

        Box(
            modifier = Modifier
                .fillMaxWidth()
                .folioPanel(com.folio.reader.ui.theme.FolioShapes.card)
        ) {
            Column(
                modifier = Modifier.padding(16.dp),
                verticalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                Row(verticalAlignment = Alignment.CenterVertically) {
                    androidx.compose.material3.Icon(
                        imageVector = if (isConnected) androidx.compose.material.icons.Icons.Filled.CheckCircle
                        else androidx.compose.material.icons.Icons.Filled.Info,
                        contentDescription = null,
                        tint = if (isConnected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.size(28.dp)
                    )
                    Spacer(Modifier.width(12.dp))
                    Column {
                        Text(
                            if (isConnected) "Cloud sync is active" else "Cloud sync is off",
                            style = MaterialTheme.typography.titleMedium
                        )
                        Text(
                            if (isConnected)
                                "Syncing via project \"${settings.firebaseProjectId}\""
                            else
                                "Connect your Firebase account to sync books, progress and annotations across devices.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    }
                }

                OutlinedTextField(
                    value = projectId,
                    onValueChange = { projectId = it },
                    label = { Text("Firebase Project ID") },
                    placeholder = { Text("my-folio-project") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = apiKey,
                    onValueChange = { apiKey = it },
                    label = { Text("Firebase Web API Key") },
                    placeholder = { Text("AIza...") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountEmail,
                    onValueChange = { accountEmail = it },
                    label = { Text("Sync Account Email (Optional)") },
                    placeholder = { Text("Same email on all devices to link sync") },
                    singleLine = true,
                    modifier = Modifier.fillMaxWidth()
                )
                OutlinedTextField(
                    value = accountPassword,
                    onValueChange = { accountPassword = it },
                    label = { Text("Sync Account Password (Optional)") },
                    placeholder = { Text("Same password across devices") },
                    singleLine = true,
                    visualTransformation = PasswordVisualTransformation(),
                    modifier = Modifier.fillMaxWidth()
                )
                Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                    Button(
                        onClick = {
                            onSettingsChange(
                                settings.copy(
                                    firebaseApiKey = apiKey.trim(),
                                    firebaseProjectId = projectId.trim(),
                                    syncAccountEmail = accountEmail.trim(),
                                    syncAccountPassword = accountPassword.trim(),
                                    cloudSyncEnabled = true // Enable cloud sync when credentials saved
                                )
                            )
                        },
                        enabled = apiKey.isNotBlank() && projectId.isNotBlank() &&
                                (apiKey != settings.firebaseApiKey || projectId != settings.firebaseProjectId ||
                                        accountEmail != settings.syncAccountEmail || accountPassword != settings.syncAccountPassword)
                    ) {
                        Text("Save")
                    }
                    if (isConnected) {
                        OutlinedButton(onClick = {
                            apiKey = ""
                            projectId = ""
                            accountEmail = ""
                            accountPassword = ""
                            onSettingsChange(
                                settings.copy(
                                    firebaseApiKey = "",
                                    firebaseProjectId = "",
                                    syncAccountEmail = "",
                                    syncAccountPassword = "",
                                    cloudSyncEnabled = false
                                )
                            )
                        }) {
                            Text("Disconnect")
                        }
                    }
                }

                // Cloud sync opt-in toggle and granular controls
                if (isConnected) {
                    HorizontalDivider(modifier = Modifier.padding(vertical = 8.dp))
                    Text("Sync Preferences", style = MaterialTheme.typography.titleSmall)

                    // Master cloud sync toggle
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text("Enable cloud sync", style = MaterialTheme.typography.bodyMedium)
                            Text(
                                "Sync your data across devices",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurfaceVariant
                            )
                        }
                        Switch(
                            checked = settings.cloudSyncEnabled,
                            onCheckedChange = { onSettingsChange(settings.copy(cloudSyncEnabled = it)) }
                        )
                    }

                    // Granular sync options (only shown when cloud sync enabled)
                    if (settings.cloudSyncEnabled) {
                        Spacer(Modifier.height(8.dp))

                        SyncToggleRow(
                            label = "Reading progress",
                            description = "Sync position across sessions & devices",
                            checked = settings.syncPositions,
                            onCheckedChange = { onSettingsChange(settings.copy(syncPositions = it)) }
                        )
                        SyncToggleRow(
                            label = "Annotations",
                            description = "Highlights, notes & bookmarks",
                            checked = settings.syncAnnotations,
                            onCheckedChange = { onSettingsChange(settings.copy(syncAnnotations = it)) }
                        )
                        SyncToggleRow(
                            label = "Reading settings",
                            description = "Typography & appearance preferences",
                            checked = settings.syncSettings,
                            onCheckedChange = { onSettingsChange(settings.copy(syncSettings = it)) }
                        )
                    }
                }
            }
        }
    }
}

@Composable
private fun SyncToggleRow(
    label: String,
    description: String,
    checked: Boolean,
    onCheckedChange: (Boolean) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(vertical = 4.dp),
        horizontalArrangement = Arrangement.SpaceBetween,
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(modifier = Modifier.weight(1f).padding(end = 16.dp)) {
            Text(label, style = MaterialTheme.typography.bodyMedium)
            Text(
                description,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
        }
        Switch(checked = checked, onCheckedChange = onCheckedChange)
    }
}
