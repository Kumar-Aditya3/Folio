package com.folio.reader.ui.document

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.unit.IntSize
import com.folio.reader.model.DocumentFormat
import com.folio.reader.model.DocumentLocator
import com.folio.reader.ui.reader.ReaderTool
import org.jsoup.Jsoup
import kotlin.math.roundToInt

data class DocumentNavigationPoint(
    val locator: DocumentLocator,
    val normalizedProgress: Double,
    val mode: DocumentReaderMode = DocumentReaderMode.SINGLE_PAGE,
    val rotationDegrees: Int = 0,
    val zoom: Float = 1f,
)

data class DocumentNavigationPreview(
    val origin: DocumentNavigationPoint,
    val target: DocumentNavigationPoint,
    val label: String,
    val excerpt: String? = null,
    val loading: Boolean = false,
)

data class FixedPageNavigationRequest(
    val locator: DocumentLocator.FixedPage,
    val zoom: Float,
    val nonce: Long,
)

internal val documentReaderTools = listOf(ReaderTool.Pages, ReaderTool.Saved, ReaderTool.Settings)

internal enum class DocumentReadingControl { PageFlow, Rotation, Zoom, Typography, PageLayout }

internal fun documentReadingControls(format: DocumentFormat): Set<DocumentReadingControl> =
    if (format == DocumentFormat.PDF) {
        setOf(DocumentReadingControl.PageFlow, DocumentReadingControl.Rotation, DocumentReadingControl.Zoom)
    } else {
        setOf(DocumentReadingControl.Typography, DocumentReadingControl.PageLayout)
    }

internal fun DocumentReaderState.navigationPoint(): DocumentNavigationPoint? {
    if (loadState !is DocumentReaderLoadState.Ready) return null
    val locator = when (document?.format) {
        DocumentFormat.PDF -> fixedPageLocator.copy(pageIndex = currentPage)
        null -> return null
        else -> reflowableLocator ?: reflowableLocator(progress = normalizedProgress)
    }
    return DocumentNavigationPoint(locator, normalizedProgress, mode, rotationDegrees, fixedPageZoom)
}

internal fun documentNavigationLabel(point: DocumentNavigationPoint, pageCount: Int = 0): String =
    when (val locator = point.locator) {
        is DocumentLocator.FixedPage -> buildString {
            append("Page ${locator.pageIndex + 1}")
            if (pageCount > 0) append(" of $pageCount")
        }
        is DocumentLocator.Reflowable -> "Location ${(point.normalizedProgress.coerceIn(0.0, 1.0) * 100).roundToInt()}%"
    }

internal fun reflowableChunkIndex(sectionId: String): Int? =
    sectionId.takeIf { it.startsWith("document-chunk-") }?.removePrefix("document-chunk-")?.toIntOrNull()

internal fun reflowableLocatorFraction(locator: DocumentLocator.Reflowable): Double? =
    locator.domLocator?.takeIf { it.startsWith("p::") }?.removePrefix("p::")?.toDoubleOrNull()
        ?.takeIf { it.isFinite() }?.coerceIn(0.0, 1.0)

internal fun reflowableLocatorProgress(
    locator: DocumentLocator.Reflowable,
    chunkCount: Int,
    fallback: Double,
): Double {
    val fraction = reflowableLocatorFraction(locator)
    val chunk = reflowableChunkIndex(locator.sectionId)
    return when {
        chunk != null && chunkCount > 0 -> (chunk + (fraction ?: 0.0)) / chunkCount
        fraction != null -> fraction
        else -> fallback
    }.coerceIn(0.0, 1.0)
}

internal fun reflowableLanding(progress: Double, chunkCount: Int): Pair<Int, Float> {
    val count = chunkCount.coerceAtLeast(1)
    val scaled = progress.coerceIn(0.0, 1.0) * count
    val index = scaled.toInt().coerceIn(0, count - 1)
    return index to (scaled - index).toFloat().coerceIn(0f, 1f)
}

/** Read only a bounded part of an already-loaded chunk, never a second browser or PDF renderer. */
internal fun documentPreviewExcerpt(html: String, fraction: Float = 0f): String? {
    val text = Jsoup.parse(html).body().text().trim()
    if (text.isEmpty()) return null
    val start = (fraction.coerceIn(0f, 1f) * (text.length - 1)).toInt()
        .coerceAtMost((text.length - 320).coerceAtLeast(0))
    return text.substring(start, (start + 640).coerceAtMost(text.length)).trim()
}

internal fun fixedPagePanLocator(pageIndex: Int, offset: Offset, page: IntSize, zoom: Float): DocumentLocator.FixedPage =
    DocumentLocator.FixedPage(
        pageIndex = pageIndex,
        normalizedX = if (page.width > 0) (offset.x / (page.width * zoom)).toDouble() else 0.0,
        normalizedY = if (page.height > 0) (offset.y / (page.height * zoom)).toDouble() else 0.0,
    )

internal fun fixedPagePanOffset(locator: DocumentLocator.FixedPage, viewport: IntSize, page: IntSize, zoom: Float): Offset =
    clampPageOffset(
        Offset((locator.normalizedX * page.width * zoom).toFloat(), (locator.normalizedY * page.height * zoom).toFloat()),
        viewport, page, zoom,
    )
