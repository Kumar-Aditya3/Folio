package com.folio.reader.ui.components

import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.nestedscroll.NestedScrollConnection
import androidx.compose.ui.input.nestedscroll.NestedScrollSource
import androidx.compose.ui.input.nestedscroll.nestedScroll
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.collectLatest

/** Scroll updates change quality twice per gesture, never once per animation frame. */
@Stable
internal class FolioGlassWorkload {
    var moving by mutableStateOf(false)
        private set
    val scrolls = MutableStateFlow(0L)

    fun scrolled() {
        moving = true
        scrolls.value += 1L
    }

    fun settled() { moving = false }
}

internal val LocalGlassWorkload = compositionLocalOf { FolioGlassWorkload() }

@Composable
internal fun rememberGlassWorkload(): FolioGlassWorkload {
    val workload = remember { FolioGlassWorkload() }
    LaunchedEffect(workload) {
        workload.scrolls.collectLatest {
            // A short dwell includes fling tails and prevents quality flickering
            // between wheel ticks. No source capture or geometry is changed.
            delay(180)
            workload.settled()
        }
    }
    return workload
}

@Composable
internal fun Modifier.folioGlassScrollObserver(): Modifier {
    val workload = LocalGlassWorkload.current
    val observer = remember(workload) {
        object : NestedScrollConnection {
            override fun onPostScroll(consumed: Offset, available: Offset, source: NestedScrollSource): Offset {
                if (consumed != Offset.Zero) workload.scrolled()
                return Offset.Zero
            }
        }
    }
    return nestedScroll(observer)
}

/** Haze 2.0.1 uses fixed quality profiles; Folio supplies workload adaptation. */
internal fun folioGlassQuality(surfaceBudget: Float, moving: Boolean): Float {
    val budget = surfaceBudget.takeIf { it.isFinite() } ?: FolioGlassCost.pane
    val resting = when {
        budget <= FolioGlassCost.floor -> 0.20f
        budget <= FolioGlassCost.pane -> 0.45f
        else -> 0.75f
    }
    return if (moving) resting * 0.65f else resting
}
