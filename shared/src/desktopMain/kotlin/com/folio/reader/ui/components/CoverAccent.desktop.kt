package com.folio.reader.ui.components

import androidx.compose.ui.graphics.ImageBitmap

/**
 * Desktop keeps the whole-bitmap read. There is no row-at-a-time API to reach for under Skia, and
 * the memory this saves is the phone's problem, not the constraint here — so the accent tests keep
 * running against exactly the code path they were written against.
 */
internal actual fun sampleCoverGrid(bitmap: ImageBitmap, width: Int, height: Int, stride: Int): IntArray =
    coverGridFromPixelMap(bitmap, width, height, stride)
