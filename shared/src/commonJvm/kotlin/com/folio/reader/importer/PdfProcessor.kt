package com.folio.reader.importer

import java.io.File
import java.io.RandomAccessFile

internal object PdfProcessor {
    fun process(file: File, fallbackTitle: String): ProcessedDocument {
        if (file.length() < 8) throw CorruptContent("PDF is truncated")
        val header = file.inputStream().use { input ->
            val bytes = ByteArray(5)
            val count = input.read(bytes)
            bytes.copyOf(count.coerceAtLeast(0)).toString(Charsets.ISO_8859_1)
        }
        if (header != "%PDF-") throw CorruptContent("PDF header is missing")
        val tailSize = minOf(file.length(), 64L * 1024).toInt()
        val tail = RandomAccessFile(file, "r").use { input ->
            input.seek(file.length() - tailSize)
            ByteArray(tailSize).also(input::readFully).toString(Charsets.ISO_8859_1)
        }
        if (!tail.contains("%%EOF")) throw CorruptContent("PDF end marker is missing")

        val pagePattern = Regex("/Type\\s*/Page(?!s)\\b")
        val countPattern = Regex("/Count\\s+(\\d+)")
        val titlePattern = Regex("/Title\\s*\\(([^)]{1,512})\\)")
        val authorPattern = Regex("/Author\\s*\\(([^)]{1,512})\\)")
        var pages = 0
        var declaredMax = 0
        var title: String? = null
        var author: String? = null
        var carry = ""
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) {
                val read = input.read(buffer)
                if (read < 0) break
                val carryLength = carry.length
                val chunk = carry + buffer.copyOf(read).toString(Charsets.ISO_8859_1)
                pages += pagePattern.findAll(chunk).count { it.range.last >= carryLength }
                countPattern.findAll(chunk).filter { it.range.last >= carryLength }.forEach {
                    declaredMax = maxOf(declaredMax, it.groupValues[1].toIntOrNull() ?: 0)
                }
                if (Regex("/Encrypt\\b").findAll(chunk).any { it.range.last >= carryLength }) {
                    throw EncryptedContent("Encrypted PDFs are not supported")
                }
                if (title == null) title = titlePattern.find(chunk)?.groupValues?.get(1)?.let(::decodePdfText)
                if (author == null) author = authorPattern.find(chunk)?.groupValues?.get(1)?.let(::decodePdfText)
                if (maxOf(pages, declaredMax) > MAX_PDF_PAGES) throw ContentTooLarge("PDF exceeds 20,000 pages")
                carry = chunk.takeLast(1024)
            }
        }
        val count = maxOf(pages, declaredMax)
        if (count == 0) throw CorruptContent("PDF page tree was not found")
        return ProcessedDocument(title = title ?: fallbackTitle, author = author, pageCount = count)
    }

    private fun decodePdfText(value: String) = value.replace(Regex("\\\\([()\\\\])"), "$1").trim()
}
