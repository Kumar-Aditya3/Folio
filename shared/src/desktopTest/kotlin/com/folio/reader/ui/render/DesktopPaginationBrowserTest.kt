package com.folio.reader.ui.render

import com.folio.reader.model.Highlight
import com.folio.reader.settings.Theme
import java.io.File
import java.nio.file.Files
import java.util.concurrent.TimeUnit
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import org.junit.jupiter.api.Assumptions.assumeTrue

/** Opt in with FOLIO_READER_BROWSER=<Chromium executable>; no npm dependencies required. */
class DesktopPaginationBrowserTest {
    @Test fun longChaptersUseBoundedSurfacesAndKeepNavigationAndReflowAnchored() {
        val browser = System.getenv("FOLIO_READER_BROWSER")
        assumeTrue(!browser.isNullOrBlank(), "Set FOLIO_READER_BROWSER to run Chromium rendering checks")
        val directory = Files.createTempDirectory("folio-pagination-").toFile()
        try {
            val pages = listOf(1, 2).map { columns ->
                File(directory, "reader-$columns.html").apply {
                    val css = "body{font:20px/1.6 Georgia;background:#f5f0e5;color:#27251f}p{padding-bottom:16px}" + PageEngine.css(24f, 24f)
                    val prose = (0..239).joinToString("") { index ->
                        "<p id='paragraph-$index' data-folio-narration='passage$index'>Paragraph $index. " +
                            "A reader should keep the same words in view when the page changes. ".repeat(if (index == 80) 180 else 12) + "</p>"
                    }
                    val highlight = Highlight("kept-passage", "book", "chapter", 0, "/0/200:0", "/0/200:8",
                        "A reader", deviceId = "render-test")
                    val paint = HighlightPaint.js(listOf(highlight), Theme.getPreset("paper"))
                    writeText("<!doctype html><meta charset='utf-8'><style id='folio-reader-style'>$css</style><body>$prose" +
                        "<script>document.currentScript.remove();${PageEngine.js(.5f, columns, 24f, 720)}\n$paint</script></body>")
                }
            }
            val script = generateSequence(File(System.getProperty("user.dir"))) { it.parentFile }
                .map { File(it, "scripts/verify_desktop_pagination.cjs") }.first { it.isFile }
            val process = ProcessBuilder(listOf("node", script.absolutePath, checkNotNull(browser)) + pages.map { it.absolutePath })
                .redirectErrorStream(true).redirectOutput(File(directory, "result.log")).start()
            assertTrue(process.waitFor(90, TimeUnit.SECONDS).also { if (!it) process.destroyForcibly() }, "Chromium verification timed out")
            val output = File(directory, "result.log").readText()
            assertEquals(0, process.exitValue(), output)
            println(output)
        } finally {
            directory.deleteRecursively()
        }
    }
}
