package com.folio.reader.narration

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.AppPalette
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import java.util.concurrent.atomic.AtomicInteger

class NarrationDismissTest {
    @get:Rule val compose = createComposeRule()

    @Test fun onlyCompletedRightSwipeDismissesAndChildControlsStillTap() {
        val dismissed = AtomicInteger()
        val taps = AtomicInteger()
        compose.setContent {
            FolioTheme.AppTheme(palette = AppPalette.LIGHT) {
                Box(Modifier.size(320.dp, 64.dp).testTag("player")
                    .narrationSwipeToDismiss("book") { dismissed.incrementAndGet() }) {
                    Text("Open narration", Modifier.clickable { taps.incrementAndGet() })
                }
            }
        }
        compose.onNodeWithText("Open narration").performClick()
        assertEquals(1, taps.get())
        val player = compose.onNodeWithTag("player")
        player.performTouchInput { swipe(Offset(width * .8f, height / 2f), Offset(width * .1f, height / 2f)) }
        compose.waitForIdle()
        assertEquals(0, dismissed.get())
        player.performTouchInput { swipe(Offset(width * .1f, height / 2f), Offset(width * .3f, height / 2f)) }
        compose.waitForIdle()
        assertEquals(0, dismissed.get())
        player.performTouchInput {
            down(Offset(width * .1f, height / 2f))
            moveTo(Offset(width * .8f, height / 2f))
            cancel()
        }
        compose.waitForIdle()
        assertEquals(0, dismissed.get())
        player.performTouchInput { swipe(Offset(width * .1f, height / 2f), Offset(width * .9f, height / 2f)) }
        compose.waitForIdle()
        assertEquals(1, dismissed.get())
        player.performSemanticsAction(SemanticsActions.Dismiss)
        assertEquals("A completed swipe must stop only once", 1, dismissed.get())
    }

    @Test fun accessibilityCanDismissWithoutDragging() {
        val dismissed = AtomicInteger()
        compose.setContent {
            FolioTheme.AppTheme(palette = AppPalette.LIGHT) {
                Box(Modifier.size(320.dp, 64.dp).testTag("player")
                    .narrationSwipeToDismiss("book") { dismissed.incrementAndGet() })
            }
        }
        compose.onNodeWithTag("player").performSemanticsAction(SemanticsActions.Dismiss)
        assertEquals(1, dismissed.get())
    }
}
