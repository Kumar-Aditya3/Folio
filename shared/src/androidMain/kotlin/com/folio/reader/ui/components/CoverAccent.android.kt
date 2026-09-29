package com.folio.reader.ui.components

import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asAndroidBitmap

/**
 * Reads the accent sampling grid off the software bitmap one row at a time, into a buffer reused
 * across rows, so sampling costs a row instead of the whole image.
 *
 * The pixels themselves are unchanged: same grid points, same opaque-ARGB packing. `getPixels` is
 * unavailable on a hardware-config bitmap and [asAndroidBitmap] throws for an `ImageBitmap` that
 * did not come from a Bitmap — either falls back to the whole-bitmap read rather than sampling
 * differently, so a fast path can never change a cover's colour.
 */
internal actual fun sampleCoverGrid(bitmap: ImageBitmap, width: Int, height: Int, stride: Int): IntArray =
    runCatching {
        val source = bitmap.asAndroidBitmap()
        val cols = (width - 1) / stride + 1
        val out = IntArray(cols * ((height - 1) / stride + 1))
        val row = IntArray(width)
        var at = 0
        var y = 0
        while (y < height) {
            source.getPixels(row, 0, width, 0, y, width, 1)
            var i = 0
            for (col in 0 until cols) {
                out[at++] = (255 shl 24) or (row[i] and 0x00FFFFFF)
                i += stride
            }
            y += stride
        }
        out
    }.getOrElse { coverGridFromPixelMap(bitmap, width, height, stride) }
