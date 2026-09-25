package com.folio.reader.ui.document

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

actual suspend fun openFixedPageDocument(path: String): FixedPageDocument = withContext(Dispatchers.IO) {
    val file = File(path)
    if (!file.isFile) throw FixedPageException(DocumentReaderErrorKind.MISSING_FILE, "The PDF file is missing")
    val descriptor = try {
        ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    } catch (error: FileNotFoundException) {
        throw FixedPageException(DocumentReaderErrorKind.MISSING_FILE, "The PDF file is missing", error)
    }
    try {
        AndroidFixedPageDocument(descriptor, PdfRenderer(descriptor))
    } catch (cancelled: CancellationException) {
        descriptor.close()
        throw cancelled
    } catch (error: SecurityException) {
        descriptor.close()
        throw FixedPageException(DocumentReaderErrorKind.ENCRYPTED, "Password-protected PDFs are not supported", error)
    } catch (error: Throwable) {
        descriptor.close()
        throw FixedPageException(DocumentReaderErrorKind.CORRUPT, "The PDF could not be opened", error)
    }
}

private class AndroidFixedPageDocument(
    private val descriptor: ParcelFileDescriptor,
    private val renderer: PdfRenderer
) : FixedPageDocument {
    private val mutex = Mutex()
    // Native teardown runs here, off the UI thread, once the in-flight render
    // releases the mutex (see close()), so dispose never blocks the caller.
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    @Volatile private var closed = false
    override val pageCount: Int = renderer.pageCount

    /** Dimensions come from openPage, which must close promptly and is cached — a 500-page document asks for every page. */
    private val aspectRatios = HashMap<Int, Float>()

    override suspend fun pageAspectRatio(pageIndex: Int): Float? = withContext(Dispatchers.IO) {
        mutex.withLock {
            if (closed || pageIndex !in 0 until pageCount) return@withLock null
            aspectRatios.getOrPut(pageIndex) {
                renderer.openPage(pageIndex).use { page ->
                    page.width.coerceAtLeast(1).toFloat() / page.height.coerceAtLeast(1)
                }
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
            renderer.openPage(request.pageIndex).use { page ->
                val rotated = request.normalizedRotation == 90 || request.normalizedRotation == 270
                val naturalWidth = if (rotated) page.height else page.width
                val naturalHeight = if (rotated) page.width else page.height
                val scale = min(
                    request.targetWidthPx.toFloat() / naturalWidth.coerceAtLeast(1),
                    request.targetHeightPx.toFloat() / naturalHeight.coerceAtLeast(1)
                ).coerceAtLeast(0.05f)
                val width = max(1, (page.width * scale).roundToInt())
                val height = max(1, (page.height * scale).roundToInt())
                val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
                bitmap.eraseColor(Color.WHITE)
                page.render(bitmap, null, null, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
                coroutineContext.ensureActive()
                if (request.normalizedRotation == 0) {
                    bitmap.asImageBitmap()
                } else {
                    val matrix = Matrix().apply { postRotate(request.normalizedRotation.toFloat()) }
                    val rotatedBitmap = Bitmap.createBitmap(bitmap, 0, 0, width, height, matrix, true)
                    bitmap.recycle()
                    rotatedBitmap.asImageBitmap()
                }
            }
        }
    }

    override fun close() {
        // Dispose must not block the UI thread. Flag the document closed
        // immediately so any waiting or later render bails, then free the
        // native renderer and descriptor off the UI thread once the in-flight
        // render releases the mutex. PdfRenderer.close throws while a page is
        // open, so the mutex hand-off is still required; it just happens on IO
        // now instead of under runBlocking on the caller thread.
        if (closed) return
        closed = true
        scope.launch {
            try {
                mutex.withLock {
                    renderer.close()
                    descriptor.close()
                }
            } catch (_: Throwable) {
                // Best-effort teardown: nothing actionable if the native
                // handles fail to close.
            }
        }.invokeOnCompletion { scope.cancel() }
    }
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
