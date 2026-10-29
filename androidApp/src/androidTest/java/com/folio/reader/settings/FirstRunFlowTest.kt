package com.folio.reader.settings

import androidx.compose.material3.Text
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createComposeRule
import androidx.test.platform.app.InstrumentationRegistry
import com.folio.reader.ui.theme.AppPalette
import com.folio.reader.ui.theme.FolioTheme
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import java.util.UUID

class FirstRunFlowTest {
    @get:Rule val compose = createComposeRule()

    @Test fun welcomePrecedesEveryChoiceAndOnlyFinishCompletesTheInstall() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val preferenceName = "first_run_test_${UUID.randomUUID()}"
        val state = FirstRunState(context, preferenceName, freshInstallation = true)
        try {
            compose.setContent {
                FolioTheme.AppTheme(palette = AppPalette.WARM) {
                    if (state.pending) FirstRunWizard(state.step, { state.moveTo(state.step.next()) },
                        { state.moveTo(state.step.previous()) }, state::finish) { Text("Optional choices for ${it.name}") }
                    else Text("Home mounted after setup")
                }
            }
            compose.onNodeWithText("Welcome to Folio").assertIsDisplayed()
            compose.onNodeWithText("Choose my setup").performClick()
            compose.onNodeWithText(FirstRunStep.Appearance.title).assertIsDisplayed()
            compose.onNodeWithText("Continue").performClick()
            compose.onNodeWithText(FirstRunStep.Echoes.title).assertIsDisplayed()
            compose.onNodeWithText("Back").performClick()
            compose.onNodeWithText(FirstRunStep.Appearance.title).assertIsDisplayed()
            assertTrue(FirstRunState(context, preferenceName, freshInstallation = false).pending)
            for (expected in listOf(FirstRunStep.Echoes, FirstRunStep.Narration, FirstRunStep.Notifications, FirstRunStep.Ready)) {
                compose.onNodeWithText("Continue").performClick()
                compose.onNodeWithText(expected.title).assertIsDisplayed()
            }
            assertTrue(state.pending)
            compose.onNodeWithText("Start reading").performClick()
            compose.runOnIdle { assertFalse(state.pending) }
            compose.onNodeWithText("Home mounted after setup").assertIsDisplayed()
            assertFalse(FirstRunState(context, preferenceName, freshInstallation = true).pending)
        } finally {
            context.getSharedPreferences(preferenceName, 0).edit().clear().commit()
        }
    }

    @Test fun lightAndDarkChoicesApplyToBothTheAppAndTheReadingPage() {
        var selected = com.folio.reader.settings.ReaderSettings()
        compose.setContent {
            var settings by remember { mutableStateOf(selected) }
            FolioTheme.AppTheme(palette = AppPalette.byId(settings.appThemeId)) {
                FirstRunWizard(FirstRunStep.Appearance, {}, {}, {}) {
                    FirstRunAppearance(settings) { settings = it; selected = it }
                }
            }
        }
        compose.onNodeWithText("Dark", substring = false).performClick()
        compose.runOnIdle {
            assertEquals("dark", selected.appThemeId)
            assertEquals("charcoal", selected.themeId)
        }
        compose.onNodeWithText("Light", substring = false).performClick()
        compose.runOnIdle {
            assertEquals("light", selected.appThemeId)
            assertEquals("classic", selected.themeId)
        }
    }

    @Test fun useDefaultsDoesNotRequireModelDownloadsOrNotificationPermission() {
        var completed = false
        compose.setContent {
            FolioTheme.AppTheme(palette = AppPalette.WARM) {
                FirstRunWizard(FirstRunStep.Welcome, { error("Defaults must not enter a feature step") }, {},
                    { completed = true }) { Text("No downloads started") }
            }
        }
        compose.onNodeWithText("Use defaults").performClick()
        compose.runOnIdle { assertTrue(completed) }
    }
}
