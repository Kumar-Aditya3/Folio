package com.folio.reader.ui.components

import androidx.compose.runtime.Composable

/**
 * A system-back interception, consumed and stopped here.
 *
 * Bulk selection on the shelves needs back to clear the selection *before*
 * anything else can see the press — the host-level handler chains the whole
 * app through one `when`, and a tab's back stack can outrank it. This exists
 * so the screen that owns the state can claim the press directly; Android
 * gives the deepest active interceptor the event, which is exactly the screen
 * showing the selection bar.
 *
 * Desktop has no system back: the actual is a no-op and Escape keeps driving
 * whatever it drove before.
 */
@Composable
expect fun FolioBackHandler(enabled: Boolean = true, onBack: () -> Unit)
