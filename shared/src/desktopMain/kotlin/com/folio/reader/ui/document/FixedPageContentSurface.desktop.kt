package com.folio.reader.ui.document

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.toComposeImageBitmap
import java.awt.Color
import java.awt.Graphics2D
import java.awt.RenderingHints
import java.awt.image.BufferedImage
import java.io.File
import java.io.IOException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import org.apache.pdfbox.Loader
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.rendering.ImageType
import org.apache.pdfbox.rendering.PDFRenderer
import org.apache.pdfbox.pdmodel.encryption.InvalidPasswordException
import kotlin.coroutines.coroutineContext
import kotlin.math.min

actual suspend fun openFixedPageDocument(path: String): FixedPageDocument = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.isFile) throw FixedPageException(DocumentReaderErrorKind.MISSING_FILE, "The PDF file is missing")
    val document = try {
        Loader.loadPDF(file)
    } catch (error: InvalidPasswordException) {
        throw FixedPageException(DocumentReaderErrorKind.ENCRYPTED, "Password-protected PDFs are not supported", error)
    } catch (error: IOException) {
        throw FixedPageException(DocumentReaderErrorKind.CORRUPT, "The PDF could not be opened", error)
    }
    DesktopFixedPageDocument(document)
}

private class DesktopFixedPageDocument(private val document: PDDocument) : FixedPageDocument {
    private val renderer = PDFRenderer(document)
    private val mutex = Mutex()
    // Teardown runs here, off the UI thread, once the in-flight render releases
    // the mutex (see close()), so dispose never blocks the caller.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false
    override val pageCount: Int = document.numberOfPages

    /** cropBox + /Rotate, remembered per page — the strip asks for every page. */
    private val aspectRatios = HashMap<Int, Float>()

    override suspend fun pageAspectRatio(pageIndex: Int): Float? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock null
            aspectRatios.getOrPut(pageIndex) {
                val page = document.getPage(pageIndex)
                val crop = page.cropBox
                val pageRotation = ((page.rotation % 360) + 360) % 360
                val rotated = pageRotation == 90 || pageRotation == 270
                val width = (if (rotated) crop.height else crop.width).coerceAtLeast(1f)
                val height = (if (rotated) crop.width else crop.height).coerceAtLeast(1f)
                width / height
            }
        }
    }

    override suspend fun render(request: FixedPageRenderRequest): ImageBitmap = withContext(Dispatchers.IO) {
        mutex.withLock {
            coroutineContext.ensureActive()
            if (closed) throw FixedPageException(DocumentReaderErrorKind.RENDER, "The PDF is closed")
            if (request.pageIndex !in 0 until pageCount) {
                throw FixedPageException(DocumentReaderErrorKind.RENDER, "PDF page is out of range")
            }
            val page = document.getPage(request.pageIndex)
            val crop = page.cropBox
            val pageRotation = ((page.rotation % 360) + 360) % 360
            val sourceRotated = pageRotation == 90 || pageRotation == 270
            val naturalWidth = if (sourceRotated) crop.height else crop.width
            val naturalHeight = if (sourceRotated) crop.width else crop.height
            val requestedRotated = request.normalizedRotation == 90 || request.normalizedRotation == 270
            val targetWidth = if (requestedRotated) request.targetHeightPx else request.targetWidthPx
            val targetHeight = if (requestedRotated) request.targetWidthPx else request.targetHeightPx
            val scale = min(
                targetWidth / naturalWidth.coerceAtLeast(1f),
                targetHeight / naturalHeight.coerceAtLeast(1f)
            ).coerceAtLeast(0.05f)
            val rendered = renderer.renderImage(request.pageIndex, scale, ImageType.RGB)
            coroutineContext.ensureActive()
            rotate(rendered, request.normalizedRotation).toComposeImageBitmap()
        }
    }

    override fun close() {
        // Dispose must not block the UI thread. Flag the document closed
        // immediately so any waiting or later render bails, then close the
        // PDDocument off the UI thread once the in-flight render releases the
        // mutex. Closing while PDFBox is still rendering would corrupt it, so
        // the mutex hand-off is still required; it just happens on IO now
        // instead of under runBlocking on the caller thread.
        if (closed) return
        closed = true
        scope.launch {
            try {
                mutex.withLock {
                    document.close()
                }
            } catch (_: Throwable) {
                // Best-effort teardown: nothing actionable if closing fails.
            }
        }.invokeOnCompletion { scope.cancel() }
    }
}

private fun rotate(source: BufferedImage, degrees: Int): BufferedImage {
    if (degrees == 0) return source
    val swap = degrees == 90 || degrees == 270
    val target = BufferedImage(
        if (swap) source.height else source.width,
        if (swap) source.width else source.height,
        BufferedImage.TYPE_INT_RGB
    )
    val graphics = target.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, target.width, target.height)
    graphics.setRenderingHint(RenderingHints.KEY_INTERPOLATION, RenderingHints.VALUE_INTERPOLATION_BICUBIC)
    graphics.translate(target.width / 2.0, target.height / 2.0)
    graphics.rotate(Math.toRadians(degrees.toDouble()))
    graphics.drawImage(source, -source.width / 2, -source.height / 2, null)
    graphics.dispose()
    source.flush()
    return target
}

@Composable
actual fun FixedPageContentSurface(
    path: String,
    documentId: String,
    pageIndex: Int,
    mode: DocumentReaderMode,
    rotationDegrees: Int,
    resetZoomKey: Int,
    modifier: Modifier,
    onDocumentOpened: (Int) -> Unit,
    onCurrentPageChanged: (Int) -> Unit,
    onTap: () -> Unit,
    onError: (DocumentReaderError) -> Unit
) = FixedPageSurfaceImpl(
    path, documentId, pageIndex, mode, rotationDegrees, resetZoomKey, modifier,
    onDocumentOpened, onCurrentPageChanged, onTap, onError
)
