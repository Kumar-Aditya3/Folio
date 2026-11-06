package com.folio.reader.ui.render

import java.nio.file.Files
import org.jsoup.Jsoup
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import com.folio.reader.settings.ReaderSettings

class DesktopReaderEncodingTest {
    private val passage = "‘Quoted’ — café • Καλημέρα हिंदी 日本語"

    @Test fun encodingComesFirstWhileReaderStylesStillOverridePublisherStyles() {
        val html = "<html><head><meta charset='windows-1252'><style id='publisher'>body{color:red}</style>" +
            "</head><body><p>$passage</p></body></html>"
        val styled = injectReaderCss(html, ReaderSettings())
        assertTrue(styled.indexOf("<meta charset=\"utf-8\"") < styled.indexOf("windows-1252"))
        assertTrue(styled.indexOf("id=\"folio-reader-style\"") > styled.indexOf("id='publisher'"))
    }

    @Test fun cachedPagesOverrideLegacyPublisherEncodingWithoutChangingText() {
        for (declaration in listOf(
            "<meta http-equiv='content-type' content='text/html; charset=iso-8859-1'>",
            "<meta charset='windows-1252'>",
            "<meta charset='utf-8'>",
            "",
        )) {
            val file = Files.createTempFile("folio-encoding-", ".html").toFile()
            try {
                // The host used to append its UTF-8 meta after the publisher's long head.
                val html = "<html><head>$declaration<style>" + "/* publisher CSS */".repeat(1500) +
                    "</style><meta charset='utf-8'></head><body><p>$passage</p></body></html>"
                writeDesktopReaderDocument(file, html)
                val document = file.inputStream().use { Jsoup.parse(it, null as String?, file.toURI().toString()) }
                assertEquals(passage, document.selectFirst("p")?.text(), declaration)
                assertEquals(html, file.readText(Charsets.UTF_8).removePrefix("\uFEFF"))
            } finally { file.delete() }
        }
    }

    @Test fun fragmentsAndExistingBomKeepNonAsciiTextIntact() {
        for (prefix in listOf("", "\uFEFF")) {
            val file = Files.createTempFile("folio-encoding-", ".html").toFile()
            try {
                writeDesktopReaderDocument(file, "$prefix<p>$passage</p>")
                val document = file.inputStream().use { Jsoup.parse(it, null as String?, file.toURI().toString()) }
                assertEquals(passage, document.selectFirst("p")?.text())
                assertEquals("\uFEFF<p>$passage</p>", file.readText(Charsets.UTF_8))
            } finally { file.delete() }
        }
    }
}
