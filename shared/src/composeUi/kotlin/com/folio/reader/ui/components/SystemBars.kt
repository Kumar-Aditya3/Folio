package com.folio.reader.ui.components

import androidx.compose.runtime.Composable

/**
 * Ties the system bars to the reader overlay: [visible] shows the status/nav
 * bars like the chrome, hides them immersive otherwise. No-op on desktop.
 */
@Composable
expect fun ReaderSystemBars(visible: Boolean)
