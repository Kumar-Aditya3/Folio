package com.folio.reader.ui.settings

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.importer.LibraryScanScope
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.theme.FolioTokens

/**
 * Hosted state for [LibraryScanSettingsPanel]: the hosts own this, load it from
 * the raw scan keys and refresh it after scope/folder changes and scans.
 */
data class LibraryScanPanelState(
    val scope: LibraryScanScope = LibraryScanScope.OFF,
    val folderDescription: String? = null,
    val deviceRootDescription: String? = null,
    val scanOnStart: Boolean = false,
    val lastScanSummary: String? = null,
    val isScanning: Boolean = false,
    /** Transient access problem (a grant that did not stick), shown under the location rows. */
    val accessError: String? = null,
)

/**
 * Library scanning settings, shared verbatim by the Android settings screen and
 * the desktop settings surface: pick a scope (off, one folder, or the whole
 * device), optionally re-scan on app start, and run a scan on demand. Files
 * found by a scan are imported through the same pipeline as hand-picked ones,
 * so already-imported books come back as duplicates and never double up.
 */
@Composable
fun LibraryScanSettingsPanel(
    state: LibraryScanPanelState,
    onScopeChange: (LibraryScanScope) -> Unit,
    onPickFolder: () -> Unit,
    onPickDeviceRoot: (() -> Unit)? = null,
    onScanOnStartChange: (Boolean) -> Unit,
    onScanNow: () -> Unit,
    /** Android grants the device scope through a permission flow, not a folder pick. */
    deviceEmptyLabel: String = "Not granted yet — choose your storage root",
    deviceGrantLabel: String = "Choose",
) {
    Column {
        Text(
            "Find ebooks and documents outside Folio and import them. " +
                "Scanning is safe to repeat — files already in your library are skipped.",
            style = MaterialTheme.typography.bodySmall,
            color = MaterialTheme.colorScheme.onSurfaceVariant
        )
        Spacer(Modifier.padding(FolioTokens.space2))
        ScanScopeRow(
            title = "Off",
            subtitle = "No scanning; import only what you pick yourself",
            selected = state.scope == LibraryScanScope.OFF,
            onClick = { onScopeChange(LibraryScanScope.OFF) }
        )
        ScanScopeRow(
            title = "Selected folder",
            subtitle = "Scan one folder you choose",
            selected = state.scope == LibraryScanScope.FOLDER,
            onClick = { onScopeChange(LibraryScanScope.FOLDER) }
        )
        ScanScopeRow(
            title = "Whole device",
            subtitle = "Scan everywhere you grant Folio access to",
            selected = state.scope == LibraryScanScope.DEVICE,
            onClick = { onScopeChange(LibraryScanScope.DEVICE) }
        )

        if (state.scope == LibraryScanScope.FOLDER) {
            FolioRule(Modifier.padding(vertical = FolioTokens.space2))
            ScanLocationRow(
                label = "Folder to scan",
                location = state.folderDescription,
                emptyLabel = "No folder chosen yet",
                onPick = onPickFolder
            )
        }
        if (state.scope == LibraryScanScope.DEVICE) {
            FolioRule(Modifier.padding(vertical = FolioTokens.space2))
            // The device scope is only as wide as the access the user grants:
            // Android asks for storage access (a permission flow), desktop scans
            // the user's common folders (no grant needed there, so no picker).
            ScanLocationRow(
                label = "Device access",
                location = state.deviceRootDescription,
                emptyLabel = deviceEmptyLabel,
                chooseLabel = deviceGrantLabel,
                onPick = onPickDeviceRoot
            )
        }
        val accessError = state.accessError
        if (accessError != null) {
            Text(
                accessError,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.error,
                modifier = Modifier.padding(top = 4.dp)
            )
        }

        FolioRule(Modifier.padding(vertical = FolioTokens.space2))
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .toggleable(
                    value = state.scanOnStart,
                    enabled = state.scope != LibraryScanScope.OFF,
                    onValueChange = onScanOnStartChange,
                    role = Role.Switch
                ),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(2.dp)
            ) {
                Text("Scan on app start", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Quietly import anything new each time Folio opens",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = state.scanOnStart,
                onCheckedChange = null,
                enabled = state.scope != LibraryScanScope.OFF,
            )
        }
        Spacer(Modifier.padding(FolioTokens.space1))
        OutlinedButton(
            onClick = onScanNow,
            enabled = state.scope != LibraryScanScope.OFF && !state.isScanning,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text(if (state.isScanning) "Scanning…" else "Scan now")
        }
        val last = state.lastScanSummary
        if (last != null) {
            Text(
                "Last scan: $last",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.padding(top = FolioTokens.space1)
            )
        }
    }
}

@Composable
private fun ScanScopeRow(
    title: String,
    subtitle: String,
    selected: Boolean,
    onClick: () -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(onClick = onClick)
            .padding(vertical = 6.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(2.dp)
        ) {
            Text(title, style = MaterialTheme.typography.bodyLarge)
            Text(subtitle, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

@Composable
private fun ScanLocationRow(
    label: String,
    location: String?,
    emptyLabel: String,
    onPick: (() -> Unit)?,
    chooseLabel: String = "Choose",
) {
    Column(modifier = Modifier.fillMaxWidth()) {
        Text(label, style = MaterialTheme.typography.bodyLarge)
        Row(
            modifier = Modifier.fillMaxWidth(),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                Icons.Filled.Folder,
                contentDescription = null,
                tint = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(Modifier.width(12.dp))
            Text(
                location ?: emptyLabel,
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier.weight(1f)
            )
            if (onPick != null) {
                TextButton(onClick = onPick) {
                    Text(if (location == null) chooseLabel else "Change", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
