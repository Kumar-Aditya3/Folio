package com.folio.reader.ui.document

import java.io.File
import kotlinx.coroutines.runBlocking
import org.apache.pdfbox.pdmodel.PDDocument
import org.apache.pdfbox.pdmodel.PDPage
import kotlin.test.Test
import kotlin.test.assertEquals
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
}
