package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.ui.unit.Dp

/**
 * Ties the system bars to the reader overlay: [visible] shows the status/nav
 * bars like the chrome, hides them immersive otherwise. No-op on desktop.
 */
@Composable
expect fun ReaderSystemBars(visible: Boolean)

/** Stable top inset for the status bar; captured once, never animates. */
@Composable
expect fun statusBarTopPadding(): Dp
