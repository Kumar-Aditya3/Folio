package com.folio.reader.ui.components

import androidx.compose.animation.*
import androidx.compose.animation.core.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Info
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.delay

/**
 * Glassy floating sync status pill that shows real-time sync state.
 * Slides in from top, auto-hides 2 seconds after a successful sync.
 */
@Composable
fun SyncIndicator(
    syncState: SyncState,
    modifier: Modifier = Modifier
) {
    var showSuccess by remember { mutableStateOf(false) }
    var showError by remember { mutableStateOf(false) }
    var wasSyncing by remember { mutableStateOf(false) }

    LaunchedEffect(syncState.isSyncing, syncState.lastError) {
        if (syncState.isSyncing) {
            wasSyncing = true
            showError = false
        } else if (wasSyncing && syncState.lastError == null) {
            wasSyncing = false
            showSuccess = true
            delay(2000)
            showSuccess = false
        } else if (syncState.lastError != null) {
            wasSyncing = false
            showError = true
            delay(4000)
            showError = false
        } else {
            wasSyncing = false
            showError = false
        }
    }

    val isVisible = (syncState.isSyncing || showError || showSuccess) && syncState.isConfigured

    AnimatedVisibility(
        visible = isVisible,
        enter = fadeIn() + slideInVertically(initialOffsetY = { -it }),
        exit = fadeOut() + slideOutVertically(targetOffsetY = { -it }),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier.padding(16.dp),
            shape = RoundedCornerShape(24.dp),
            color = when {
                syncState.lastError != null -> MaterialTheme.colorScheme.errorContainer
                showSuccess -> MaterialTheme.colorScheme.tertiaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer.copy(alpha = 0.92f)
            },
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncPillIcon(syncState, showSuccess)
                SyncPillText(syncState, showSuccess)
                SyncPillUploadProgress(syncState)
            }
        }
    }
}

@Composable
private fun SyncPillIcon(syncState: SyncState, showSuccess: Boolean) {
    when {
        syncState.lastError != null -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Sync error",
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp)
        )
        showSuccess -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Sync complete",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(20.dp)
        )
        syncState.isSyncing -> {
            val pulse = rememberInfiniteTransition(label = "sync-pulse")
            val alpha by pulse.animateFloat(
                initialValue = 0.35f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(700, easing = FastOutSlowInEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "sync-alpha"
            )
            Icon(
                imageVector = Icons.Filled.Refresh,
                contentDescription = "Syncing",
                tint = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha }
            )
        }
    }
}

@Composable
private fun SyncPillText(syncState: SyncState, showSuccess: Boolean) {
    Text(
        text = when {
            syncState.lastError != null -> "Sync failed"
            showSuccess -> "Synced"
            syncState.isSyncing -> {
                val pending = syncState.pendingUploadCount + syncState.pendingDownloadCount
                if (pending > 0) "Syncing $pending items..." else "Syncing..."
            }
            else -> ""
        },
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        color = when {
            syncState.lastError != null -> MaterialTheme.colorScheme.onErrorContainer
            showSuccess -> MaterialTheme.colorScheme.onTertiaryContainer
            else -> MaterialTheme.colorScheme.onPrimaryContainer
        }
    )
}

@Composable
private fun SyncPillUploadProgress(syncState: SyncState) {
    syncState.storageProgress.values.firstOrNull()?.let { progress ->
        if (progress.percent > 0f && progress.percent < 1f) {
            Text(
                text = "${(progress.percent * 100).toInt()}%",
                style = MaterialTheme.typography.labelSmall,
                color = MaterialTheme.colorScheme.onPrimaryContainer.copy(alpha = 0.7f)
            )
        }
    }
}

/**
 * Compact sync status card for the Settings screen.
 * Shows current sync state and a "Sync Now" button when idle and connected.
 */
@Composable
fun SyncStatusCard(
    syncState: SyncState,
    isConnected: Boolean,
    onSyncNow: () -> Unit,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = when {
                !isConnected -> MaterialTheme.colorScheme.surfaceVariant
                syncState.lastError != null -> FolioTheme.colors.errorContainer.copy(alpha = 0.4f)
                else -> MaterialTheme.colorScheme.surfaceVariant
            }
        ),
        elevation = CardDefaults.cardElevation(defaultElevation = 0.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = when {
                    !isConnected -> Icons.Filled.Info
                    syncState.lastError != null -> Icons.Filled.Warning
                    syncState.isSyncing -> Icons.Filled.Refresh
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    !isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    syncState.lastError != null -> FolioTheme.colors.error
                    syncState.isSyncing -> FolioTheme.colors.primary
                    else -> FolioTheme.colors.primary
                },
                modifier = Modifier.size(20.dp)
            )
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = when {
                        !isConnected -> "Not configured"
                        syncState.lastError != null -> "Sync error"
                        syncState.isSyncing -> "Syncing…"
                        syncState.pendingUploadCount > 0 -> "Changes waiting to sync"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (syncState.lastError != null) {
                    Text(
                        text = syncState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = FolioTheme.colors.error
                    )
                }
            }
            if (isConnected && !syncState.isSyncing) {
                Button(onClick = onSyncNow) {
                    Text("Sync Now", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
