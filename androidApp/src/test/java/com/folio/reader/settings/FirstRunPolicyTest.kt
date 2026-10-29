package com.folio.reader.settings

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class FirstRunPolicyTest {
    @Test fun freshInstallStartsWithWelcome() {
        assertTrue(needsFirstRunSetup(false, false, false, true))
        assertEquals(FirstRunStep.Welcome, FirstRunStep.entries.first())
    }
    @Test fun updatesDoNotInterruptExistingReaders() {
        assertFalse(needsFirstRunSetup(false, false, false, false))
        assertFalse(needsFirstRunSetup(false, true, false, true))
        assertFalse(needsFirstRunSetup(true, false, true, true))
    }
    @Test fun anInterruptedFreshSetupResumesAfterAnUpdate() {
        assertTrue(needsFirstRunSetup(false, false, true, false))
    }
    @Test fun choicesFollowWelcomeAndBackDoesNotCompleteSetup() {
        assertEquals(listOf("Welcome", "Appearance", "Echoes", "Narration", "Notifications", "Ready"),
            FirstRunStep.entries.map { it.name })
        for (step in FirstRunStep.entries.dropLast(1)) assertEquals(step, step.next().previous())
        assertEquals(FirstRunStep.Welcome, FirstRunStep.Welcome.previous())
        assertEquals(FirstRunStep.Ready, FirstRunStep.Ready.next())
    }
}
