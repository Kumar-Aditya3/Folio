package com.folio.reader.ui.document

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import kotlinx.coroutines.CancellationException
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DocumentReaderCoreTest {
    @Test
    fun pageProgressClampsPageAndHandlesSmallDocuments() {
        assertEquals(0.0, DocumentReaderViewModel.pageProgress(4, 0))
        assertEquals(1.0, DocumentReaderViewModel.pageProgress(0, 1))
        assertEquals(0.0, DocumentReaderViewModel.pageProgress(-3, 5))
        assertEquals(1.0, DocumentReaderViewModel.pageProgress(99, 5))
        assertEquals(0.5, DocumentReaderViewModel.pageProgress(2, 5))
    }

    @Test
    fun cacheEvictsLeastRecentlyUsedEntryByByteBudget() {
        val cache = PixelBudgetLruCache<String, ByteArray>(6) { it.size.toLong() }
        cache.put("first", ByteArray(3))
        cache.put("second", ByteArray(3))
        cache["first"]
        cache.put("third", ByteArray(3))

        assertNull(cache["second"])
        assertEquals(3, cache["first"]?.size)
        assertEquals(3, cache["third"]?.size)
        assertEquals(6, cache.sizeBytes)
    }

    @Test
    fun oversizedCacheEntryIsNotRetained() {
        val cache = PixelBudgetLruCache<String, ByteArray>(4) { it.size.toLong() }
        cache.put("large", ByteArray(8))
        assertNull(cache["large"])
        assertEquals(0, cache.size)
        assertEquals(0, cache.sizeBytes)
    }

    @Test
    fun cancellationIsNotMappedToReaderError() {
        assertNull(CancellationException("The coroutine scope left the composition").toReaderErrorOrNull())
        assertEquals(DocumentReaderErrorKind.CORRUPT, FixedPageException(DocumentReaderErrorKind.CORRUPT, "bad").toReaderErrorOrNull()?.kind)
        assertEquals(DocumentReaderErrorKind.RENDER, IllegalStateException("bad").toReaderErrorOrNull()?.kind)
    }

    @Test
    fun singlePageGestureClassifiesSwipeTapAndVerticalMotion() {
        assertEquals(FixedPageGesture.NEXT_PAGE, singlePageGesture(-100f, 4f, 400f))
        assertEquals(FixedPageGesture.PREVIOUS_PAGE, singlePageGesture(100f, 4f, 400f))
        assertEquals(FixedPageGesture.TAP, singlePageGesture(3f, -2f, 400f))
        assertEquals(FixedPageGesture.NONE, singlePageGesture(90f, 85f, 400f))
        assertEquals(FixedPageGesture.NONE, singlePageGesture(40f, 1f, 400f))
    }

    @Test
    fun pageFitAndClampUseActualLetterboxedGeometry() {
        val viewport = IntSize(400, 600)
        val fitted = fittedPageSize(1000, 1000, viewport)
        assertEquals(IntSize(400, 400), fitted)
        val fitOffset = clampPageOffset(Offset(200f, -200f), viewport, fitted, 1f)
        assertEquals(0f, fitOffset.x)
        assertEquals(0f, fitOffset.y)
        val zoomedOffset = clampPageOffset(Offset(500f, -500f), viewport, fitted, 2f)
        assertEquals(200f, zoomedOffset.x)
        assertEquals(-100f, zoomedOffset.y)
    }
}
