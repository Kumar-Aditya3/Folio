package com.folio.reader.ui.settings

import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Folder
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Switch
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.onVerticalWheel
import com.folio.reader.ui.theme.flipThemeMode
import kotlinx.coroutines.launch

@OptIn(ExperimentalLayoutApi::class)
@Composable
fun GeneralSettingsPanel(
    settings: ReaderSettings,
    onSettingsChange: (ReaderSettings) -> Unit,
    onImportFont: () -> Unit,
    mangaDownloadsLocation: String? = null,
    onPickMangaDownloadsLocation: () -> Unit = {}
) {
    // The picker's mode: the app palette's own polarity, or the custom theme's
    // when one is active (the switch is disabled then, but the cards still
    // preview the face matching what the user is looking at).
    val modeDark = settings.customAppTheme?.isDark
        ?: com.folio.reader.ui.theme.AppPalette.byId(settings.appThemeId).isDark
    val resolvedAppPaletteId = com.folio.reader.ui.theme.AppPalette.byId(settings.appThemeId).id
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp)
    ) {
        // Theme packs: one theme, two faces. The switch applies the active
        // pack's other face; tapping a card applies the face being shown.
        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Column(modifier = Modifier.weight(1f)) {
                    Text("Theme packs", style = MaterialTheme.typography.bodyLarge)
                    Text(
                        "Every pack carries a light side and a dark side. The switch " +
                            "flips the pair; either side can still be adjusted on its own.",
                        style = MaterialTheme.typography.bodySmall,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )
                }
                Spacer(Modifier.width(12.dp))
                // The picker's mode follows the app palette's own polarity. A
                // custom app theme owns its polarity, so the switch is disabled
                // while one is active — the theme maker is where that lives.
                ThemeModeToggle(
                    dark = modeDark,
                    enabled = settings.customAppTheme == null,
                    onToggle = { onSettingsChange(flipThemeMode(settings)) },
                )
            }
            // Horizontal scrollable bar of live mini previews, the in-reader
            // theme slider turned sideways: opens on the active pack, tap applies.
            // A bare LazyRow is not wheel-scrollable on desktop (the old FlowRow
            // complaint), so vertical wheel deltas are mapped to horizontal scrolls.
            val packs = com.folio.reader.ui.theme.ThemePack.ALL
            val packListState = rememberLazyListState()
            val packScrollScope = rememberCoroutineScope()
            // Resolve through byId: a persisted id may still name a palette that
            // folded into a surviving family, and the pack must still light up.
            fun packSelected(pack: com.folio.reader.ui.theme.ThemePack): Boolean =
                settings.customTheme == null && settings.customAppTheme == null &&
                    ((resolvedAppPaletteId == pack.lightAppPaletteId &&
                        settings.themeId == pack.lightReaderThemeId) ||
                        (resolvedAppPaletteId == pack.darkAppPaletteId &&
                            settings.themeId == pack.darkReaderThemeId))
            val selectedPackIndex = packs.indexOfFirst(::packSelected).coerceAtLeast(0)
            LaunchedEffect(Unit) {
                packListState.scrollToItem(index = selectedPackIndex)
            }
            LazyRow(
                state = packListState,
                modifier = Modifier
                    .fillMaxWidth()
                    .onVerticalWheel { delta ->
                        packScrollScope.launch { packListState.scrollBy(delta * 60f) }
                    },
                horizontalArrangement = Arrangement.spacedBy(8.dp),
                contentPadding = PaddingValues(horizontal = 2.dp, vertical = 2.dp)
            ) {
                items(packs, key = { it.id }) { pack ->
                    ThemePackCard(
                        pack = pack,
                        dark = modeDark,
                        selected = packSelected(pack),
                        onClick = {
                            // Picking a pack is how you leave a custom theme behind;
                            // the card applies the face the picker is showing.
                            onSettingsChange(
                                settings.copy(
                                    appThemeId = pack.appPaletteId(modeDark),
                                    themeId = pack.readerThemeId(modeDark),
                                    customTheme = null,
                                    customAppTheme = null,
                                )
                            )
                        },
                    )
                }
            }
        }

        Column(
            modifier = Modifier.fillMaxWidth(),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Typeface", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Display and UI font pairing for the app chrome.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            LazyRow(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                items(com.folio.reader.ui.theme.FontTheme.entries) { ft ->
                    FontThemeCard(
                        fontTheme = ft,
                        selected = settings.fontThemeId == ft.id,
                        onClick = { onSettingsChange(settings.copy(fontThemeId = ft.id)) },
                    )
                }
            }
        }

        // §13.3: hero tint sampled from the current book's cover. On by default —
        // the contrast guard keeps the hero legible even on white/black covers.
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.SpaceBetween,
            verticalAlignment = Alignment.CenterVertically
        ) {
            Column(modifier = Modifier.weight(1f)) {
                Text("Tint Home from book cover", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Colour the Home hero from the book you are reading, " +
                        "contrast-checked so text stays readable.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Switch(
                checked = settings.homeCoverTint,
                onCheckedChange = { onSettingsChange(settings.copy(homeCoverTint = it)) }
            )
        }

        // Import custom font button
        OutlinedButton(
            onClick = onImportFont,
            modifier = Modifier.fillMaxWidth()
        ) {
            Text("Import custom font")
        }

        // Where downloaded manga chapters live — same control as the Downloads
        // screen's location card, surfaced here so it is findable from Settings.
        if (mangaDownloadsLocation != null) {
            Column(modifier = Modifier.fillMaxWidth()) {
                Text("Manga downloads", style = MaterialTheme.typography.bodyLarge)
                Text(
                    "Where downloaded chapters are stored.",
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
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
                    mangaDownloadsLocation,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant,
                    maxLines = 1,
                    overflow = androidx.compose.ui.text.style.TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f)
                )
                TextButton(onClick = onPickMangaDownloadsLocation) {
                    Text("Change", style = MaterialTheme.typography.labelMedium)
                }
            }
        }
    }
}
