package com.folio.reader.ui.settings

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Text
import androidx.compose.runtime.CompositionLocalProvider
import com.folio.reader.ui.theme.LocalFolioSurfaceOpacity
import com.folio.reader.ui.theme.surfaceOpacity
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch

/** Uses the real native materials, including their current capability and motion preferences. */
@Composable
fun FolioMaterialsPreview(settings: ReaderSettings, modifier: Modifier = Modifier) {
    val scope = rememberCoroutineScope()
    val motion = rememberMotionEnabled()
    var lifted by remember { mutableStateOf(false) }
    var reset by remember { mutableStateOf<Job?>(null) }
    val depth = animateFloatAsState(if (lifted && motion) 1f else 0f,
        animationSpec = rememberMotionAnimation(MotionKey.PressScale), label = "materialPreview")
    CompositionLocalProvider(
        LocalFolioSurfaceOpacity provides settings.surfaceOpacity(),
        com.folio.reader.ui.theme.LocalFolioVibrancy provides com.folio.reader.ui.theme.FolioVibrancy(settings.vibrancy),
        com.folio.reader.ui.theme.LocalFolioSheen provides com.folio.reader.ui.theme.FolioSheen(settings.metallicSheen),
    ) {
    FolioArtworkScene(FolioArtwork("preview:binding", null), modifier.fillMaxWidth(), enabled = settings.ambientColor) {
        Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
            FolioEyebrow("Your reading room", accent = FolioTheme.colors.accentDiscovery)
            Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(18.dp)) {
                FolioCoverPlate(null, "Small Worlds", "A. Reader", width = 74.dp, small = true,
                    modifier = Modifier.graphicsLayer {
                        rotationY = depth.value * -12f
                        translationY = depth.value * -6.dp.toPx()
                    })
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(5.dp)) {
                    Text("A place for the next chapter", style = FolioTheme.typography.headlineSmall,
                        color = FolioTheme.colors.onSurface)
                    Text("Artwork, pages and light", style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant)
                }
            }
            FolioGlassPanel {
                Text("Soft light. Clear controls.", style = FolioTheme.typography.titleMedium,
                    color = FolioTheme.colors.onSurface)
                Text("More, settings and statistics share this surface.",
                    style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.onSurfaceVariant)
            }
            FolioActionDock("Preview motion", if (motion) "Tap to lift the cover" else "Reduced motion is active", {
                reset?.cancel()
                lifted = true
                reset = scope.launch { delay(450); lifted = false }
            }, progress = .63f)
        }
    }
    }
}
