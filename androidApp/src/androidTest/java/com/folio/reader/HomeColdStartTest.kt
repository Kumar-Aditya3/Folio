package com.folio.reader

import androidx.compose.ui.test.*
import androidx.compose.ui.test.junit4.createAndroidComposeRule
import org.junit.Rule
import org.junit.Test

/** Runs against the existing install: no library rows, settings or permissions are changed. */
class HomeColdStartTest {
    @get:Rule val compose = createAndroidComposeRule<MainActivity>()

    @Test fun homeAppearsOnInitialEntryAndActivityRecreationWithoutVisitingAnotherTab() {
        fun assertHome() {
            compose.waitUntil(30_000) {
                runCatching {
                    compose.onAllNodesWithText("Folio", useUnmergedTree = true).fetchSemanticsNodes().isNotEmpty()
                }.getOrDefault(false)
            }
            compose.onNodeWithText("Folio", useUnmergedTree = true).assertIsDisplayed()
        }
        assertHome()
        compose.activityRule.scenario.recreate()
        assertHome()
    }
}
