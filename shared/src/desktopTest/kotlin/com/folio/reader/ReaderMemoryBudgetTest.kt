package com.folio.reader

import com.folio.reader.narration.NarrationActivity
import com.folio.reader.util.ReaderMemoryBudget
import kotlin.test.*

class ReaderMemoryBudgetTest {
    @Test fun imageCachesShareABoundedBudgetAndLeaveSpeechHeadroom() {
        val oldHeap = ReaderMemoryBudget.heapMegabytes
        val oldLow = ReaderMemoryBudget.lowRam
        try {
            ReaderMemoryBudget.heapMegabytes = 512; ReaderMemoryBudget.lowRam = false
            val normal = ReaderMemoryBudget.imageBytes
            assertTrue(ReaderMemoryBudget.coverBytes * 2 + ReaderMemoryBudget.pageBytes + ReaderMemoryBudget.encodedBytes <= normal)
            NarrationActivity.inferenceActive = true
            assertTrue(ReaderMemoryBudget.imageBytes < normal)
            assertEquals(2,ReaderMemoryBudget.prefetchAhead)
            assertTrue(ReaderMemoryBudget.pagePixels <= ReaderMemoryBudget.pageBytes / 4)
            NarrationActivity.inferenceActive = false
            ReaderMemoryBudget.lowRam = true
            assertTrue(ReaderMemoryBudget.imageBytes <= 64L * 1024 * 1024)
        } finally {
            ReaderMemoryBudget.heapMegabytes = oldHeap; ReaderMemoryBudget.lowRam = oldLow
            NarrationActivity.inferenceActive = false
        }
    }
}
