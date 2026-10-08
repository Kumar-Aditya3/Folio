package com.folio.reader.ui.components

import androidx.activity.compose.BackHandler
import androidx.compose.runtime.Composable

@Composable
actual fun FolioBackHandler(enabled: Boolean, onBack: () -> Unit) {
    BackHandler(enabled = enabled, onBack = onBack)
}
