package com.folio.reader.ui.components

import androidx.compose.ui.Modifier

actual fun Modifier.onVerticalWheel(onScroll: (deltaY: Float) -> Unit): Modifier = this
