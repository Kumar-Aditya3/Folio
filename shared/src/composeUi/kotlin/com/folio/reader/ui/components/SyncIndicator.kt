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
import androidx.compose.material.icons.outlined.CloudQueue
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
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.sync.SyncState
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.delay

private enum class SyncPillPhase { HIDDEN, SYNCING, SUCCESS, ERROR, QUOTA_LIMITED }

val QuotaAmber = Color(0xFFE6A23C)
private val QuotaAmberContainer = Color(0xFFFFF3E0)
private val QuotaAmberOnContainer = Color(0xFF7A5900)
private val QuotaAmberContainerDark = Color(0xFF3E2E10)
private val QuotaAmberOnContainerDark = Color(0xFFFFDDB3)

@Composable
private fun quotaContainerColor(): Color {
    val lum = FolioTheme.colors.background.red * 0.2126f +
            FolioTheme.colors.background.green * 0.7152f +
            FolioTheme.colors.background.blue * 0.0722f
    return if (lum < 0.45f) QuotaAmberContainerDark else QuotaAmberContainer
}

@Composable
private fun quotaOnContainerColor(): Color {
    val lum = FolioTheme.colors.background.red * 0.2126f +
            FolioTheme.colors.background.green * 0.7152f +
            FolioTheme.colors.background.blue * 0.0722f
    return if (lum < 0.45f) QuotaAmberOnContainerDark else QuotaAmberOnContainer
}

/**
 * Floating sync status pill. Only materialises when a sync visibly takes time —
 * the periodic loop's quick no-op cycles stay silent — then flashes a short
 * confirmation after a real sync. Errors stay up for a few seconds.
 */
@Composable
fun SyncIndicator(
    syncState: SyncState,
    modifier: Modifier = Modifier
) {
    var phase by remember { mutableStateOf(SyncPillPhase.HIDDEN) }
    // The gentle quota notice appears once per reader session; periodic cycles must not
    // make it re-pop after it dismissed (the top-bar icon keeps the state visible).
    var quotaShown by remember { mutableStateOf(false) }

    LaunchedEffect(syncState.isSyncing, syncState.lastError, syncState.quotaLimited) {
        when {
            syncState.quotaLimited -> {
                if (!quotaShown) {
                    quotaShown = true
                    phase = SyncPillPhase.QUOTA_LIMITED
                }
            }

            syncState.isSyncing -> {
                delay(400)
                phase = SyncPillPhase.SYNCING
            }

            syncState.lastError != null -> {
                phase = SyncPillPhase.ERROR
            }

            else -> {
                phase = if (phase == SyncPillPhase.SYNCING) SyncPillPhase.SUCCESS else SyncPillPhase.HIDDEN
            }
        }
    }

    // Auto-hide timers live in their own phase-keyed effect: the periodic sync loop
    // toggles isSyncing, which restarts the state effect above and used to cancel the
    // pending hide, parking the pill over the reader forever.
    LaunchedEffect(phase) {
        when (phase) {
            SyncPillPhase.QUOTA_LIMITED -> delay(6000)
            SyncPillPhase.ERROR -> delay(4000)
            SyncPillPhase.SUCCESS -> delay(1600)
            else -> return@LaunchedEffect
        }
        phase = SyncPillPhase.HIDDEN
    }

    AnimatedVisibility(
        visible = phase != SyncPillPhase.HIDDEN && syncState.isConfigured,
        enter = fadeIn(tween(220)) + slideInVertically(animationSpec = tween(260), initialOffsetY = { -it / 2 }),
        exit = fadeOut(tween(200)) + slideOutVertically(animationSpec = tween(240), targetOffsetY = { -it / 2 }),
        modifier = modifier
    ) {
        Surface(
            modifier = Modifier
                .padding(16.dp)
                .animateContentSize()
                .then(
                    if (phase == SyncPillPhase.QUOTA_LIMITED)
                        Modifier.semantics { contentDescription = "Sync is resting — Google's free quota reached, it resumes automatically." }
                    else Modifier
                ),
            shape = RoundedCornerShape(24.dp),
            color = when (phase) {
                SyncPillPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
                SyncPillPhase.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
                SyncPillPhase.QUOTA_LIMITED -> quotaContainerColor()
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            tonalElevation = 6.dp,
            shadowElevation = 4.dp
        ) {
            Row(
                modifier = Modifier.padding(horizontal = 16.dp, vertical = 10.dp),
                horizontalArrangement = Arrangement.spacedBy(10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                SyncPillIcon(phase)
                SyncPillText(phase)
                SyncPillUploadProgress(syncState)
            }
        }
    }
}

@Composable
private fun SyncPillIcon(phase: SyncPillPhase) {
    when (phase) {
        SyncPillPhase.ERROR -> Icon(
            imageVector = Icons.Filled.Warning,
            contentDescription = "Sync error",
            tint = MaterialTheme.colorScheme.onErrorContainer,
            modifier = Modifier.size(20.dp)
        )

        SyncPillPhase.SUCCESS -> Icon(
            imageVector = Icons.Filled.CheckCircle,
            contentDescription = "Sync complete",
            tint = MaterialTheme.colorScheme.onTertiaryContainer,
            modifier = Modifier.size(20.dp)
        )

        SyncPillPhase.QUOTA_LIMITED -> {
            val breathe = rememberInfiniteTransition(label = "quota-breathe")
            val alpha by breathe.animateFloat(
                initialValue = 0.55f,
                targetValue = 1f,
                animationSpec = infiniteRepeatable(
                    animation = tween(2400, easing = LinearEasing),
                    repeatMode = RepeatMode.Reverse
                ),
                label = "quota-alpha"
            )
            Icon(
                imageVector = Icons.Outlined.CloudQueue,
                contentDescription = "Sync paused — quota reached",
                tint = quotaOnContainerColor(),
                modifier = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha }
            )
        }

        SyncPillPhase.SYNCING -> {
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

        SyncPillPhase.HIDDEN -> Unit
    }
}

@Composable
private fun SyncPillText(phase: SyncPillPhase) {
    Text(
        text = when (phase) {
            SyncPillPhase.ERROR -> "Sync failed"
            SyncPillPhase.SUCCESS -> "Synced"
            SyncPillPhase.SYNCING -> "Syncing\u2026"
            SyncPillPhase.QUOTA_LIMITED -> "Sync resting"
            SyncPillPhase.HIDDEN -> ""
        },
        style = MaterialTheme.typography.labelMedium.copy(fontSize = 13.sp),
        color = when (phase) {
            SyncPillPhase.ERROR -> MaterialTheme.colorScheme.onErrorContainer
            SyncPillPhase.SUCCESS -> MaterialTheme.colorScheme.onTertiaryContainer
            SyncPillPhase.QUOTA_LIMITED -> quotaOnContainerColor()
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
                syncState.quotaLimited -> quotaContainerColor()
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
                    syncState.quotaLimited -> Icons.Outlined.CloudQueue
                    syncState.lastError != null -> Icons.Filled.Warning
                    syncState.isSyncing -> Icons.Filled.Refresh
                    else -> Icons.Filled.CheckCircle
                },
                contentDescription = null,
                tint = when {
                    !isConnected -> MaterialTheme.colorScheme.onSurfaceVariant
                    syncState.quotaLimited -> quotaOnContainerColor()
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
                        syncState.quotaLimited -> "Sync resting"
                        syncState.lastError != null -> "Sync error"
                        syncState.isSyncing -> "Syncing\u2026"
                        syncState.pendingUploadCount > 0 -> "Changes waiting to sync"
                        else -> "Up to date"
                    },
                    style = MaterialTheme.typography.labelLarge,
                    fontWeight = FontWeight.Medium
                )
                if (syncState.quotaLimited) {
                    Text(
                        text = "Google's free quota reached \u2014 sync resumes automatically.",
                        style = MaterialTheme.typography.bodySmall,
                        color = quotaOnContainerColor()
                    )
                }
                if (syncState.lastError != null) {
                    Text(
                        text = syncState.lastError,
                        style = MaterialTheme.typography.bodySmall,
                        color = FolioTheme.colors.error
                    )
                }
            }
            if (isConnected) {
                Button(onClick = onSyncNow, enabled = !syncState.isSyncing) {
                    Text("Sync Now", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
