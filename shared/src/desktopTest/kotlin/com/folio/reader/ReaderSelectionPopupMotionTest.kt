package com.folio.reader

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.ExperimentalComposeUiApi
import androidx.compose.ui.ImageComposeScene
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.ReduceMotionMode
import com.folio.reader.ui.reader.AnimatedReaderSelectionTools
import com.folio.reader.ui.theme.LocalMotion
import com.folio.reader.ui.theme.MotionTier
import com.folio.reader.ui.theme.resolveMotion
import javax.swing.SwingUtilities
import kotlin.test.*

@OptIn(ExperimentalComposeUiApi::class)
class ReaderSelectionPopupMotionTest {
    @Test fun normalMotionRetainsPopupUntilItsExitCompletes() = onUi {
        checkDismissal(reduced = false)
    }

    @Test fun reducedMotionDismissesWithoutAnAnimatedTail() = onUi {
        checkDismissal(reduced = true)
    }

    private fun checkDismissal(reduced: Boolean) {
        val visible = mutableStateOf(false)
        var attached = 0
        val motion = resolveMotion(MotionPreferences().let {
            if (reduced) it.copy(reduceMotion = ReduceMotionMode.REDUCED) else it
        }, MotionTier.STANDARD, systemReduced = false)
        val scene = ImageComposeScene(320, 200) {
            CompositionLocalProvider(LocalMotion provides motion) {
                Box(Modifier.fillMaxSize()) {
                    AnimatedReaderSelectionTools(visible.value) {
                        DisposableEffect(Unit) { attached++; onDispose { attached-- } }
                        Box(Modifier.size(160.dp, 48.dp).background(Color.White))
                    }
                }
            }
        }
        fun render(ms: Long) { Snapshot.sendApplyNotifications(); scene.render(ms * 1_000_000L).close() }
        try {
            render(0)
            assertEquals(0, attached)
            visible.value = true
            render(16)
            render(400)
            assertEquals(1, attached)
            visible.value = false
            render(416)
            render(432)
            if (!reduced) assertEquals(1, attached, "An outgoing popup must remain composed while it fades")
            else assertEquals(0, attached, "Reduced motion should dismiss immediately")
            render(1000)
            render(1016)
            assertEquals(0, attached)
        } finally { scene.close() }
    }

    private fun onUi(block: () -> Unit) {
        var failure: Throwable? = null
        SwingUtilities.invokeAndWait { try { block() } catch (error: Throwable) { failure = error } }
        failure?.let { throw it }
    }
}
