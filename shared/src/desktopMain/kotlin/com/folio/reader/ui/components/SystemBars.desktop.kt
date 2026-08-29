package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/** Desktop has no system bars to manage. */
@Composable
actual fun ReaderSystemBars(visible: Boolean) {
}

@Composable
actual fun statusBarTopPadding(): Dp = 0.dp
