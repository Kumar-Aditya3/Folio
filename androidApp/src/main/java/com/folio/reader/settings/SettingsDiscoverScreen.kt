package com.folio.reader.settings

import android.content.pm.ApplicationInfo
import android.util.Log
import androidx.compose.runtime.*
import androidx.compose.ui.Modifier
import androidx.compose.ui.layout.boundsInRoot
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.layout.positionInRoot
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.MangaSourceInfo
import com.folio.reader.nav.FolioNavModelImpl
import com.folio.reader.ui.components.FolioGlassPanel
import com.folio.reader.ui.settings.DiscoverSettingsPanel
import kotlinx.coroutines.flow.map

@Composable
fun SettingsDiscoverScreen(navModel: FolioNavModelImpl, onBack: () -> Unit) {
    val activity = navModel.activity
    val debuggable = activity.applicationInfo.flags and ApplicationInfo.FLAG_DEBUGGABLE != 0
    val disableClip = debuggable && activity.intent.getBooleanExtra("folio.discover.disableClip", false)
    val disableGlass = disableClip || (debuggable && activity.intent.getBooleanExtra("folio.discover.disableGlass", false))
    val traceEnabled = debuggable && activity.intent.getBooleanExtra("folio.discover.trace", false)
    val trace: ((String) -> Unit)? = remember(activity, traceEnabled) {
        if (traceEnabled) {
            { message: String -> Log.d("FolioDiscover", message); Unit }
        } else null
    }
    if (trace != null) DisposableEffect(trace, disableGlass, disableClip) {
        trace("open hazeDisabled=$disableGlass clipDisabled=$disableClip")
        onDispose { trace("close") }
    }
    val panelTrace = if (trace == null) Modifier else Modifier.onGloballyPositioned {
        trace("panel placed size=${it.size} rootPosition=${it.positionInRoot()} " +
            "rootBounds=${it.boundsInRoot()} windowBounds=${it.boundsInWindow()}")
    }
    val backend = navModel.graph.mangaBackend
    val sourceFlow = remember(backend) { backend.observeSources().map { it as List<MangaSourceInfo>? } }
    val extensionFlow = remember(backend) { backend.observeExtensions().map { it as List<ExtensionEntry>? } }
    val sources by sourceFlow.collectAsState(initial = null)
    val extensions by extensionFlow.collectAsState(initial = null)
    // Keep the existing continuous plain-scrolling panel for the controlled material experiment.
    // Device testing already ruled out the lazy/plain scroller as the cause of the dead tail.
    SettingsCategoryScaffold(
        title = "Discover",
        onBack = onBack,
        scrollBody = {
            // `clipEnabled = false` is the fix, not a diagnostic: this panel is ~11600 px tall and
            // `Modifier.clip(shape)` clips hit testing as well as drawing, so every row past a
            // certain height was painted but untappable — the Manhwa18→Weeb Central dead zone.
            // Measured on device: with clipping off, Weeb Central and Webtoons.com both toggle.
            // The rounded corners still render, because the panel's own fill is drawn as a rounded
            // outline in `drawWithCache`; the rows are rectangular and never reach the corners.
            FolioGlassPanel(blurEnabled = !disableGlass, clipEnabled = false, modifier = panelTrace) {
                DiscoverSettingsPanel(navModel.globalSettings, sources.orEmpty(), extensions.orEmpty(),
                    loading = sources == null || extensions == null,
                    onSettingsChange = { navModel.updateSettings(it) }, onDiagnosticTrace = trace)
            }
        },
    )
}
