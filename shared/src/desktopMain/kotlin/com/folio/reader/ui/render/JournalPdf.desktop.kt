package com.folio.reader.ui.render

import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withTimeout
import org.cef.browser.CefBrowser
import org.cef.handler.CefDisplayHandlerAdapter
import org.cef.misc.CefPdfPrintSettings
import java.awt.EventQueue
import java.io.File
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

/** A dedicated browser never navigates or captures the open book. */
internal suspend fun exportJournalPdf(html: String, destination: File) = withTimeout(60_000) {
    suspendCancellableCoroutine<Unit> { continuation ->
        val document = File.createTempFile("folio-print-", ".html")
        document.writeText(html.replace("</body>", "<script>Promise.all([document.fonts.ready,...Array.from(document.images).map(i=>i.decode().catch(()=>{}))]).then(()=>document.title='folio-print-ready')</script></body>"))
        EventQueue.invokeLater {
            try {
                val client = JcefRuntime.app().createClient()
                val browser = client.createBrowser(document.toURI().toString(), false, false)
                val frame = javax.swing.JFrame().apply {
                    isUndecorated = true
                    setSize(800, 1000)
                    contentPane.add(browser.uiComponent)
                    // Realize the native peer without showing a window.
                    addNotify()
                }
                var started = false
                fun dispose() { browser.close(true); client.dispose(); frame.dispose(); document.delete() }
                client.addDisplayHandler(object : CefDisplayHandlerAdapter() {
                    override fun onTitleChange(b: CefBrowser, title: String?) {
                        if (title != "folio-print-ready" || started) return
                        started = true
                        b.printToPDF(destination.absolutePath, CefPdfPrintSettings().apply {
                            landscape = false; print_background = true; prefer_css_page_size = true; scale = 1.0
                        }) { _, success ->
                            EventQueue.invokeLater { dispose() }
                            if (continuation.isActive) {
                                if (success && destination.length() > 0) continuation.resume(Unit)
                                else continuation.resumeWithException(java.io.IOException("PDF generation failed"))
                            }
                        }
                    }
                })
                continuation.invokeOnCancellation { EventQueue.invokeLater { dispose() } }
                browser.createImmediately()
            } catch (e: Exception) { document.delete(); if (continuation.isActive) continuation.resumeWithException(e) }
        }
    }
}
