package com.folio.reader.ui.document

import java.io.File
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import org.apache.pdfbox.pdmodel.common.PDRectangle
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue

class DesktopFixedPageDocumentTest {
    @Test
    fun rendersARealPdfPageAndRotation() = runBlocking {
        val file = File.createTempFile("folio-reader-", ".pdf")
        try {
            PDDocument().use { pdf ->
                pdf.addPage(PDPage())
                pdf.save(file)
            }
            openFixedPageDocument(file.absolutePath).use { document ->
                assertEquals(1, document.pageCount)
                val normal = document.render(FixedPageRenderRequest(0, 400, 500))
                val rotated = document.render(FixedPageRenderRequest(0, 400, 500, 90))
                assertTrue(normal.width > 0 && normal.height > 0)
                assertTrue(rotated.width > 0 && rotated.height > 0)
                assertTrue(normal.width < normal.height)
                assertTrue(rotated.width > rotated.height)
            }
        } finally {
            file.delete()
        }
    }

    @Test
    fun pageAspectRatioReportsCropBoxOverRotate() = runBlocking {
        val file = File.createTempFile("folio-reader-", ".pdf")
        try {
            PDDocument().use { pdf ->
                // Landscape letter, rotated 90° in metadata: the page a reader
                // shows is portrait 612×792.
                val landscape = PDPage(PDRectangle(792f, 612f))
                landscape.rotation = 90
                pdf.addPage(landscape)
                pdf.addPage(PDPage(PDRectangle(612f, 792f)))
                pdf.save(file)
            }
            openFixedPageDocument(file.absolutePath).use { document ->
                // Page 0: /Rotate 90 swaps the crop box dimensions → portrait.
                assertEquals(612f / 792f, document.pageAspectRatio(0)!!, 0.01f)
                // Page 1: plain portrait letter.
                assertEquals(612f / 792f, document.pageAspectRatio(1)!!, 0.01f)
                // Repeat reads come from the cache and stay stable.
                assertEquals(612f / 792f, document.pageAspectRatio(0)!!, 0.001f)
                // Out-of-range is null, not a crash.
                assertNull(document.pageAspectRatio(-1))
                assertNull(document.pageAspectRatio(2))
            }
        } finally {
            file.delete()
        }
    }
}

