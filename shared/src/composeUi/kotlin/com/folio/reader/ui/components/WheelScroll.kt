package com.folio.reader.ui.components

import androidx.compose.ui.Modifier

/** Maps vertical mouse-wheel deltas to [onScroll]; a no-op where wheels don't exist. */
expect fun Modifier.onVerticalWheel(onScroll: (deltaY: Float) -> Unit): Modifier
