package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Home
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.Alignment
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.InternalComposeUiApi
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getAllSemanticsNodes
import androidx.compose.ui.semantics.getOrNull
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.components.FolioNavigationCapsule
import com.folio.reader.ui.components.FolioNavigationItem
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioDaylight
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.UiFonts
import java.io.File
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

@OptIn(ExperimentalComposeUiApi::class, InternalComposeUiApi::class)
class ThemeChromeTest {
    @Test fun shippingCapsuleKeepsFourUsableTabsAndMovesSelectionWhenTapped() {
        UiFonts.install(File(requireNotNull(javaClass.classLoader.getResource("fonts/spacegrotesk_variable.ttf")).toURI()).parentFile)
        val labels = listOf("Home", "Library", "Stats", "More")
        val items = labels.mapIndexed { i, label -> FolioNavigationItem("$i", Icons.Filled.Home, label) }
        val selected = mutableIntStateOf(0)
        val scene = ImageComposeScene(320, 120) {
            FolioTheme.AppTheme(AppPalette.COPPER, daylight = FolioDaylight.Neutral,
                motion = MotionPreferences(reduceMotion = ReduceMotionMode.REDUCED)) {
                Box(Modifier.fillMaxSize().background(FolioTheme.colors.background), contentAlignment = Alignment.Center) {
                    FolioNavigationCapsule(items, selected.intValue, { selected.intValue = it })
                }
            }
        }
        var frame = 0L
        fun settle() = repeat(12) {
            Snapshot.sendApplyNotifications()
            scene.render(frame++ * 150_000_000L).close()
        }
        fun tabs() = scene.semanticsOwners.flatMap { it.getAllSemanticsNodes(mergingEnabled = true) }
            .filter { it.config.getOrNull(SemanticsProperties.Role) == Role.Tab }
        try {
            settle()
            for (index in labels.indices) {
                val before = tabs()
                assertEquals(4, before.size)
                assertTrue(requireNotNull(before[index].config.getOrNull(SemanticsActions.OnClick)).action?.invoke() == true)
                settle()
                val current = tabs()
                assertEquals(index, selected.intValue)
                assertEquals(1, current.count { it.config.getOrNull(SemanticsProperties.Selected) == true })
                assertTrue(current[index].config.getOrNull(SemanticsProperties.Selected) == true)
                assertTrue(current[index].config.getOrNull(SemanticsProperties.Text).orEmpty().any { it.text == labels[index] })
                for (tab in current) {
                    assertTrue(tab.boundsInRoot.width >= 48f && tab.boundsInRoot.height >= 48f)
                    assertTrue(tab.boundsInRoot.left >= 0f && tab.boundsInRoot.right <= 320f)
                }
            }
        } finally { scene.close() }
    }
}
