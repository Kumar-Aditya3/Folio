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
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.delay

private enum class SyncPillPhase { HIDDEN, SYNCING, SUCCESS, ERROR, QUOTA_LIMITED }

// Kept because the top-bar SyncStatusBadge still tints its glyph with it. The
// pill and settings card below no longer use a hand-rolled amber container: they
// read the palette-adaptive tertiary role so quota state stays legible across all
// 30+ themes instead of assuming a light/dark amber pair via luminance.
val QuotaAmber = Color(0xFFE6A23C)

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
            shape = com.folio.reader.ui.theme.FolioShapes.pill,
            color = when (phase) {
                SyncPillPhase.ERROR -> MaterialTheme.colorScheme.errorContainer
                SyncPillPhase.SUCCESS -> MaterialTheme.colorScheme.tertiaryContainer
                SyncPillPhase.QUOTA_LIMITED -> FolioTheme.colors.tertiaryContainer
                else -> MaterialTheme.colorScheme.primaryContainer
            },
            tonalElevation = 0.dp,
            shadowElevation = FolioTokens.elevationVeil
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
            val alpha = rememberBreatheAlpha(
                initialValue = 0.55f,
                durationMillis = 2400,
                easing = LinearEasing,
                label = "quota-breathe",
            )
            Icon(
                imageVector = Icons.Outlined.CloudQueue,
                contentDescription = "Sync paused — quota reached",
                tint = FolioTheme.colors.onTertiaryContainer,
                modifier = Modifier.size(20.dp).graphicsLayer { this.alpha = alpha }
            )
        }

        SyncPillPhase.SYNCING -> {
            val alpha = rememberBreatheAlpha(
                initialValue = 0.35f,
                durationMillis = 700,
                easing = FastOutSlowInEasing,
                label = "sync-pulse",
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

/**
 * Pulsing alpha for the sync/quota glyphs. Under reduce-motion it returns a static
 * full-opacity value and never starts the infinite transition — the same
 * early-return shape as [rememberShimmerPhase], so nothing subscribes and the icon
 * simply holds still.
 */
@Composable
private fun rememberBreatheAlpha(
    initialValue: Float,
    durationMillis: Int,
    easing: Easing,
    label: String,
): Float {
    if (!rememberMotionEnabled()) return 1f
    val transition = rememberInfiniteTransition(label = label)
    val alpha by transition.animateFloat(
        initialValue = initialValue,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis, easing = easing),
            repeatMode = RepeatMode.Reverse,
        ),
        label = "$label-alpha",
    )
    return alpha
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
            SyncPillPhase.QUOTA_LIMITED -> FolioTheme.colors.onTertiaryContainer
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
    Box(
        modifier = modifier
            .fillMaxWidth()
            // Status is reference information, so it sits *in* the page as a well,
            // tinted by whatever state it is reporting.
            .folioSunken(
                com.folio.reader.ui.theme.FolioShapes.inset,
                accent = when {
                    !isConnected -> null
                    syncState.quotaLimited -> FolioTheme.colors.tertiaryContainer
                    syncState.lastError != null -> FolioTheme.colors.error
                    else -> FolioTheme.colors.accentProgress
                },
            )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 12.dp),
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
                    syncState.quotaLimited -> FolioTheme.colors.onTertiaryContainer
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
                        color = FolioTheme.colors.onTertiaryContainer
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
