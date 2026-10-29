package com.folio.reader.util

import com.folio.reader.narration.NarrationActivity

/** Native speech and WebView need headroom outside the managed image caches. */
object ReaderMemoryBudget {
    @Volatile var heapMegabytes: Int = 512
    @Volatile var lowRam: Boolean = false
    val constrained: Boolean get() = lowRam || NarrationActivity.ownerBookId != null || NarrationActivity.inferenceActive
    val imageBytes: Long get() = (heapMegabytes.toLong() * 1024 * 1024 / if (constrained) 6 else 3)
        .coerceIn(16L*1024*1024, if (constrained) 64L*1024*1024 else 184L*1024*1024)
    val coverBytes: Long get() = imageBytes / 8
    val pageBytes: Long get() = imageBytes / 2
    val encodedBytes: Long get() = imageBytes / 4
    val pagePixels: Long get() = (pageBytes / 4).coerceIn(2_097_152,16_777_216)
    val prefetchAhead: Int get() = if (constrained) 2 else 6
    val prefetchNext: Int get() = if (constrained) 1 else 4
}
