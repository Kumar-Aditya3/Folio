package com.folio.reader

import com.folio.reader.ui.theme.FolioHaptic
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * The "Living Paper" haptic vocabulary contract.
 *
 * The whole point of [FolioHaptic] is that one feel maps to one class of
 * interaction *everywhere*, so the app speaks a single tactile language. That
 * only holds if the vocabulary stays a closed, deliberate set — a stray entry
 * added at a call site (rather than reused) is the drift this guards against.
 *
 * The Android actual maps every entry to a `VibrationEffect`; the desktop actual
 * is a no-op. Neither can be exercised in a desktop unit test (no vibrator, no
 * Compose runtime here), so this pins the vocabulary itself: the exact set of
 * feels, so adding or renaming one is a conscious edit to this test too.
 */
class FolioHapticsTest {

    @Test
    fun vocabularyIsTheDeliberateClosedSet() {
        // If this list changes, the Android when-mapping in FolioHaptics.android.kt
        // must be updated in lockstep — its `when(feel)` is exhaustive with no else,
        // so a new entry there is a compile error until it is given a real waveform
        // rather than silently inheriting a default.
        val expected = setOf(
            "PageTurn",
            "Commit",
            "Confirm",
            "ScrubTick",
            "PickUp",
            "Reject",
            "Celebrate",
        )
        val actual = FolioHaptic.entries.map { it.name }.toSet()
        assertEquals(expected, actual, "FolioHaptic vocabulary drifted from its contract")
    }

    @Test
    fun everyFeelIsDistinct() {
        val names = FolioHaptic.entries.map { it.name }
        assertEquals(names.size, names.toSet().size, "duplicate feel names")
        assertTrue(FolioHaptic.entries.isNotEmpty(), "vocabulary must not be empty")
    }
}
