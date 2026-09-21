package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable

private object NoOpFolioHaptics : FolioHaptics {
    override fun play(feel: FolioHaptic) {}
}

// Desktop has no haptics; the whole vocabulary is a silent no-op there, matching
// rememberGlassTick's desktop actual.
@Composable
actual fun rememberFolioHaptics(): FolioHaptics = NoOpFolioHaptics
