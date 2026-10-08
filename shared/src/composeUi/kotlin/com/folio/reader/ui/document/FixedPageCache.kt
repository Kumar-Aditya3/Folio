package com.folio.reader.ui.document

import androidx.compose.ui.unit.IntSize

internal data class FixedPageCacheKey(
    val documentId: String,
    val pageIndex: Int,
    val widthPx: Int,
    val heightPx: Int,
    val rotationDegrees: Int,
    val thumbnail: Boolean
)

internal fun fixedPageRasterKey(
    documentId: String,
    pageIndex: Int,
    viewportSize: IntSize,
    rotationDegrees: Int,
    thumbnail: Boolean = false
): FixedPageCacheKey = FixedPageCacheKey(
    documentId = documentId,
    pageIndex = pageIndex,
    widthPx = viewportSize.width.coerceAtLeast(1),
    heightPx = viewportSize.height.coerceAtLeast(1),
    rotationDegrees = rotationDegrees,
    thumbnail = thumbnail
)

internal class PixelBudgetLruCache<K, V>(
    private val maxBytes: Long,
    private val sizeOf: (V) -> Long
) {
    init {
        require(maxBytes > 0)
    }

    private val values = LinkedHashMap<K, V>(16, 0.75f, true)
    var sizeBytes: Long = 0
        private set

    val size: Int get() = values.size

    @Synchronized
    operator fun get(key: K): V? = values[key]

    @Synchronized
    fun put(key: K, value: V) {
        values.put(key, value)?.let { sizeBytes -= sizeOf(it) }
        sizeBytes += sizeOf(value)
        val iterator = values.entries.iterator()
        while (sizeBytes > maxBytes && iterator.hasNext()) {
            sizeBytes -= sizeOf(iterator.next().value)
            iterator.remove()
        }
    }

    @Synchronized
    fun clear() {
        values.clear()
        sizeBytes = 0
    }
}
