package com.folio.reader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Badge
import androidx.compose.material3.BadgedBox
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.theme.FolioTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun SyncStatusBadge(
    syncState: SyncState,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (icon, contentDescription, tint) = when {
        syncState.isSyncing -> Triple(
            Icons.Filled.Refresh,
            "Syncing",
            FolioTheme.colors.primary
        )
        syncState.lastError != null -> Triple(
            Icons.Filled.Warning,
            "Sync error",
            FolioTheme.colors.error
        )
        else -> Triple(
            Icons.Filled.CheckCircle,
            "Synced",
            FolioTheme.colors.primary
        )
    }

    val pendingCount = syncState.pendingUploadCount + syncState.pendingDownloadCount

    IconButton(
        onClick = onClick,
        modifier = modifier
    ) {
        BadgedBox(
            badge = {
                if (pendingCount > 0 && syncState.isConfigured) {
                    Badge(
                        containerColor = FolioTheme.colors.error,
                        contentColor = FolioTheme.colors.onError
                    ) {
                        Text(
                            text = if (pendingCount > 99) "99+" else pendingCount.toString(),
                            style = MaterialTheme.typography.labelSmall
                        )
                    }
                }
            }
        ) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(24.dp)
            )
        }
    }
}

enum class SyncDisplayState {
    SYNCED, SYNCING, ERROR, OFFLINE
}

fun SyncState.toDisplayState(): SyncDisplayState {
    return when {
        isSyncing -> SyncDisplayState.SYNCING
        lastError != null -> SyncDisplayState.ERROR
        else -> SyncDisplayState.SYNCED
    }
}
