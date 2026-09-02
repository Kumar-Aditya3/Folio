package com.folio.reader.settings

import android.Manifest
import android.os.Build
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.work.MangaUpdateScheduler
import kotlinx.coroutines.delay

/**
 * §11.3: background manga chapter checks. Opt-in — Off cancels the worker,
 * Manual keeps "check now" without a schedule, 6/12/24h schedule the periodic
 * run. Selecting a periodic interval also requests the Android 13
 * POST_NOTIFICATIONS runtime permission; the worker stays silent when withheld.
 */
@Composable
fun SettingsMangaScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val context = LocalContext.current
    LaunchedEffect(Unit) {
        runCatching { navModel.globalSettings = navModel.graph.settingsRepository.getGlobalSettings() }
    }
    var checkQueuedAt by remember { mutableStateOf<Long?>(null) }
    LaunchedEffect(checkQueuedAt) {
        if (checkQueuedAt != null) {
            delay(4000)
            checkQueuedAt = null
        }
    }

    val permissionLauncher = rememberLauncherForActivityResult(ActivityResultContracts.RequestPermission()) { }

    fun applyInterval(hours: Int) {
        navModel.updateSettings(navModel.globalSettings.copy(mangaUpdateIntervalHours = hours))
        MangaUpdateScheduler.sync(context, hours)
        if (hours > 0 && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionLauncher.launch(Manifest.permission.POST_NOTIFICATIONS)
        }
    }

    val interval = navModel.globalSettings.mangaUpdateIntervalHours

    SettingsCategoryScaffold(title = "Manga updates", onBack = onBack) {
        Column {
            Text(
                "Check your library's sources for new chapters in the background. " +
                    "One summary notification per run — never one per manga.",
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant
            )
            Spacer(Modifier.height(FolioTokens.space2))
            IntervalRow(
                title = "Off",
                subtitle = "No automatic checks",
                selected = interval == 0,
                onClick = { applyInterval(0) }
            )
            IntervalRow(
                title = "Manual",
                subtitle = "No schedule — check only when you ask",
                selected = interval == MANUAL_HOURS,
                onClick = { applyInterval(MANUAL_HOURS) }
            )
            IntervalRow(
                title = "Every 6 hours",
                subtitle = "Frequent checks on Wi-Fi and battery",
                selected = interval == 6,
                onClick = { applyInterval(6) }
            )
            IntervalRow(
                title = "Every 12 hours",
                subtitle = "The recommended balance",
                selected = interval == 12,
                onClick = { applyInterval(12) }
            )
            IntervalRow(
                title = "Every 24 hours",
                subtitle = "A daily catch-up",
                selected = interval == 24,
                onClick = { applyInterval(24) }
            )
            HorizontalDivider(Modifier.padding(vertical = FolioTokens.space2))
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable {
                        MangaUpdateScheduler.runNow(context)
                        checkQueuedAt = System.currentTimeMillis()
                    }
                    .padding(vertical = 10.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        "Check now",
                        style = FolioTheme.typography.bodyLarge,
                        color = FolioTheme.colors.onSurface
                    )
                    Text(
                        "Look for new chapters immediately",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
            if (checkQueuedAt != null) {
                Text(
                    "Check queued — if new chapters are found you'll get one summary notification.",
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.accentDiscovery,
                    modifier = Modifier.padding(top = FolioTokens.space1)
                )
            }
        }
    }
}

@Composable
private fun IntervalRow(
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
            Text(title, style = FolioTheme.typography.bodyLarge, color = FolioTheme.colors.onSurface)
            Text(subtitle, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
        RadioButton(selected = selected, onClick = onClick)
    }
}

/** Storage sentinel for "no schedule, manual checks only" — the scheduler treats it as off. */
private const val MANUAL_HOURS = -1
