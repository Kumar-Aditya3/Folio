package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
import androidx.compose.material3.Checkbox
import androidx.compose.material3.RadioButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.LayoutCoordinates
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import androidx.compose.ui.layout.positionInWindow
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.settings.DiscoverMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.theme.FolioTheme

@Composable
fun DiscoverSettingsPanel(
    settings: ReaderSettings,
    sources: List<MangaSourceInfo>,
    extensions: List<ExtensionEntry>,
    loading: Boolean,
    onSettingsChange: (ReaderSettings) -> Unit,
    onDiagnosticTrace: ((String) -> Unit)? = null,
) {
    val contentTrace = if (onDiagnosticTrace == null) Modifier else Modifier.onGloballyPositioned {
        onDiagnosticTrace("content placed ${it.discoverTraceBounds()}")
    }
    Column(contentTrace, verticalArrangement = Arrangement.spacedBy(16.dp)) {
        Text("What appears on Home", style = FolioTheme.typography.titleMedium)
        listOf(
            Triple(DiscoverMode.DEFAULT, "As usual", "Latest releases from your recent reading sources. This is the default."),
            Triple(DiscoverMode.SELECTED_EXTENSIONS, "Selected extensions", "Discover only uses the extensions you choose below."),
            Triple(DiscoverMode.HIDDEN, "Hide Discover", "Remove the Discover shelf from Home."),
        ).forEach { (mode, title, detail) ->
            Row(Modifier.fillMaxWidth().selectable(settings.discoverMode == mode, role = Role.RadioButton,
                onClick = { onSettingsChange(settings.copy(discoverMode = mode)) }).padding(vertical = 6.dp),
                verticalAlignment = Alignment.CenterVertically) {
                RadioButton(settings.discoverMode == mode, onClick = null)
                Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                    Text(title, style = FolioTheme.typography.titleSmall)
                    Text(detail, style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
                }
            }
        }
        if (settings.discoverMode == DiscoverMode.SELECTED_EXTENSIONS) {
            FolioRule()
            Text("Allowed extensions", style = FolioTheme.typography.titleMedium)
            val installed = extensions.filter { it.isInstalled }.associateBy { it.pkgName }
            val sourcePackages = sources.filterNot { it.isLocal }.mapNotNull { it.extensionPkg }.toSet()
            val packages = (installed.keys + sourcePackages + settings.discoverExtensionPackages)
                .sortedWith(compareBy({ installed[it]?.name?.lowercase() ?: it.lowercase() }, { it }))
            if (settings.discoverExtensionPackages.isEmpty()) Text(
                "Choose at least one extension. Discover stays empty until you select one.",
                style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
            if (loading) Text("Loading installed extensions…", style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant)
            if (packages.isEmpty() && !loading) Text("Install an extension to choose it here.",
                style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
            packages.forEachIndexed { index, pkg ->
                val selected = pkg in settings.discoverExtensionPackages
                val available = pkg in installed || pkg in sourcePackages
                val name = installed[pkg]?.name ?: sources.firstOrNull { it.extensionPkg == pkg }?.name ?: pkg
                // Non-snapshot storage keeps the diagnostic observer out of recomposition.
                val coordinates = if (onDiagnosticTrace == null) null else remember(pkg) {
                    arrayOfNulls<LayoutCoordinates>(1)
                }
                val rowTrace = if (onDiagnosticTrace == null) Modifier else Modifier.onGloballyPositioned {
                    coordinates?.set(0, it)
                    onDiagnosticTrace("row placed index=$index pkg=$pkg name=$name selected=$selected ${it.discoverTraceBounds()}")
                }
                Row(Modifier.fillMaxWidth().toggleable(selected, role = Role.Checkbox, onValueChange = { enabled ->
                    onDiagnosticTrace?.invoke("toggle index=$index pkg=$pkg name=$name selected=$selected requested=$enabled " +
                        (coordinates?.get(0)?.takeIf { it.isAttached }?.discoverTraceBounds() ?: "unplaced"))
                    val next = if (enabled) settings.discoverExtensionPackages + pkg else settings.discoverExtensionPackages - pkg
                    onSettingsChange(settings.copy(discoverExtensionPackages = next))
                }).then(rowTrace).padding(vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
                    Checkbox(selected, onCheckedChange = null)
                    Column(Modifier.weight(1f).padding(start = 12.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Text(name, style = FolioTheme.typography.bodyLarge)
                        Text(if (!available) "Unavailable · selection retained"
                            else if (sources.any { it.extensionPkg == pkg && it.supportsLatest }) "Provides latest releases"
                            else if (loading) pkg else "No latest-release source available",
                            style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
                    }
                }
            }
            Text("Your selection is kept when you change modes. Existing exclusions still apply.",
                style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
        }
    }
}

private fun LayoutCoordinates.discoverTraceBounds(): String =
    "size=$size rootPosition=${positionInRoot()} rootBounds=${boundsInRoot()} " +
        "windowPosition=${positionInWindow()} windowBounds=${boundsInWindow()} ancestors=" +
        generateSequence(parentLayoutCoordinates) { it.parentLayoutCoordinates }
            .joinToString(" > ") { "${it.size}@${it.positionInRoot()}" }
