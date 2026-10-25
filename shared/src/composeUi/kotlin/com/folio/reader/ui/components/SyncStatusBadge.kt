package com.folio.reader.ui.components

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.background
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.outlined.CloudQueue
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.theme.FolioTheme

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
        syncState.quotaLimited -> Triple(
            Icons.Outlined.CloudQueue,
            "Sync resting — Google's free quota reached, it resumes automatically",
            QuotaAmber
        )
        else -> Triple(
            Icons.Filled.CheckCircle,
            "Synced",
            // Quiet when there is nothing to do: a filled tick in the accent was the
            // brightest object in the top bar and reported "nothing happened".
            FolioTheme.colors.onSurfaceVariant
        )
    }

    val pendingCount = syncState.pendingUploadCount + syncState.pendingDownloadCount

    IconButton(
        onClick = onClick,
        modifier = modifier.size(48.dp)
    ) {
        Box(Modifier.size(32.dp), contentAlignment = Alignment.Center) {
            Icon(
                imageVector = icon,
                contentDescription = contentDescription,
                tint = tint,
                modifier = Modifier.size(21.dp)
            )
            // Keep the queue dot inside this action's footprint and off error/
            // refresh glyphs; those states already explain why sync needs attention.
            if (pendingCount > 0 && syncState.isConfigured && !syncState.quotaLimited &&
                !syncState.isSyncing && syncState.lastError == null) {
                Box(Modifier.align(Alignment.BottomEnd).size(5.dp)
                    .background(FolioTheme.colors.primary, CircleShape))
            }
        }
    }
}

enum class SyncDisplayState {
    SYNCED, SYNCING, ERROR, OFFLINE, QUOTA_RESTING
}

fun SyncState.toDisplayState(): SyncDisplayState {
    return when {
        isSyncing -> SyncDisplayState.SYNCING
        lastError != null -> SyncDisplayState.ERROR
        quotaLimited -> SyncDisplayState.QUOTA_RESTING
        else -> SyncDisplayState.SYNCED
    }
}
