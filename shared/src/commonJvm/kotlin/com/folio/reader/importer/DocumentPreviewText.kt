package com.folio.reader.importer

import java.io.File
import org.jsoup.Jsoup

data class DocumentPreviewText(val title: String, val body: String)

/** Bound startup repair work even for very large converted documents. Never executes HTML. */
fun documentPreviewText(path: String): DocumentPreviewText {
    val text = File(path).bufferedReader().use { reader ->
        val buffer = CharArray(128 * 1024)
        var count = 0
        while (count < buffer.size) {
            val read = reader.read(buffer, count, buffer.size - count)
            if (read < 0) break
            count += read
        }
        String(buffer, 0, count)
    }
    val html = Jsoup.parse(text)
    html.select("script,style,noscript,template").remove()
    return DocumentPreviewText(html.title().take(160),
        html.body().select("h1,h2,h3,p,li,pre,td").map { it.text() }
            .filter { it.isNotBlank() }.joinToString("\n\n").ifBlank { html.body().text() }.take(2400))
}
