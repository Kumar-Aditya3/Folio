package com.folio.reader.tumble

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.folio.reader.ui.home.HomeUiState

/** Stable Home seam; the companion and arcade are now owned by the application environment. */
@Composable fun TumbleHomeEntry(home: HomeUiState, modifier: Modifier = Modifier) {
    PicaHomeEntry(home, modifier)
}

internal fun scoreText(value: Int): String = String.format(java.util.Locale.getDefault(), "%,d", value)
