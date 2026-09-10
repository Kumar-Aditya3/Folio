package com.folio.reader.importer

import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile

class DocumentFormatDetector {
    fun detect(file: File, filename: String? = file.name, mimeType: String? = null): DetectedContent {
        if (!file.isFile) throw CorruptContent("Input is not a regular file")
        if (file.length() == 0L) throw CorruptContent("Input is empty")
        if (file.length() > MAX_SOURCE_BYTES) throw ContentTooLarge("Input exceeds 512 MiB")
        val prefix = readPrefix(file, 8192)
        val content = detectContent(file, prefix)
        val extension = formatForExtension(filename?.substringAfterLast('.', "")?.lowercase())
        val mime = formatForMime(mimeType?.substringBefore(';')?.trim()?.lowercase())
        val claims = listOfNotNull(extension, mime).distinct()
        if (content != null) {
            val conflicts = claims.filter { it != content }
            if (conflicts.isNotEmpty()) throw UnsafeContent("File content conflicts with its declared type")
            return DetectedContent(content, true)
        }
        if (claims.size > 1) throw UnsafeContent("Filename and MIME type conflict")
        val claimed = claims.singleOrNull() ?: throw UnsupportedContent("Unrecognized file type")
        if (claimed != IncomingFormat.TXT && claimed != IncomingFormat.HTML) {
            throw CorruptContent("Missing required ${claimed.name} content signature")
        }
        if (prefix.any { it == 0.toByte() }) throw CorruptContent("Text input contains binary data")
        return DetectedContent(claimed, false)
    }

    private fun detectContent(file: File, prefix: ByteArray): IncomingFormat? {
        if (prefix.size >= 5 && prefix.copyOfRange(0, 5).decodeToString() == "%PDF-") return IncomingFormat.PDF
        if (prefix.size >= 4 && prefix[0] == 0x50.toByte() && prefix[1] == 0x4b.toByte()) {
            return detectZip(file)
        }
        val text = prefix.decodeToString(throwOnInvalidSequence = false)
            .trimStart('\uFEFF', ' ', '\t', '\n', '\r', '\u000C')
        if (Regex("(?is)^<(?:!doctype\\s+html|html|head|body)(?:\\s|>)").containsMatchIn(text)) return IncomingFormat.HTML
        return null
    }

    private fun detectZip(file: File): IncomingFormat = try {
        ZipFile(file).use { zip ->
            var count = 0
            var hasEpubContainer = false
            var hasContentTypes = false
            var hasWordDocument = false
            var hasOdtContent = false
            var mimetypeEntry: java.util.zip.ZipEntry? = null
            val entries = zip.entries()
            while (entries.hasMoreElements()) {
                val entry = entries.nextElement()
                if (++count > MAX_ARCHIVE_ENTRIES) throw UnsafeContent("Archive has too many entries")
                when (entry.name) {
                    "mimetype" -> mimetypeEntry = entry
                    "META-INF/container.xml" -> hasEpubContainer = true
                    "[Content_Types].xml" -> hasContentTypes = true
                    "word/document.xml" -> hasWordDocument = true
                    "content.xml" -> hasOdtContent = true
                }
            }
            val mimetype = mimetypeEntry?.let { entry ->
                zip.getInputStream(entry).use { input ->
                    val bytes = ByteArray(129)
                    val countRead = input.read(bytes).coerceAtLeast(0)
                    if (countRead > 128) throw UnsafeContent("Container MIME entry is oversized")
                    bytes.copyOf(countRead).decodeToString().trim()
                }
            }
            when {
                mimetype == "application/epub+zip" && hasEpubContainer -> IncomingFormat.EPUB
                mimetype != null && mimetype != "application/epub+zip" && hasEpubContainer -> throw UnsafeContent("EPUB container MIME conflicts with its structure")
                hasContentTypes && hasWordDocument -> IncomingFormat.DOCX
                mimetype == "application/vnd.oasis.opendocument.text" && hasOdtContent -> IncomingFormat.ODT
                else -> throw UnsupportedContent("ZIP container is not EPUB, DOCX, or ODT")
            }
        }
    } catch (e: ImportFailure) {
        throw e
    } catch (e: ZipException) {
        throw CorruptContent("Malformed ZIP container")
    }

    private fun formatForExtension(ext: String?) = when (ext) {
        "epub" -> IncomingFormat.EPUB; "pdf" -> IncomingFormat.PDF; "txt" -> IncomingFormat.TXT
        "html", "htm" -> IncomingFormat.HTML; "docx" -> IncomingFormat.DOCX; "odt" -> IncomingFormat.ODT
        else -> null
    }

    private fun formatForMime(mime: String?) = when (mime) {
        "application/epub+zip" -> IncomingFormat.EPUB
        "application/pdf" -> IncomingFormat.PDF
        "text/plain" -> IncomingFormat.TXT
        "text/html", "application/xhtml+xml" -> IncomingFormat.HTML
        "application/vnd.openxmlformats-officedocument.wordprocessingml.document" -> IncomingFormat.DOCX
        "application/vnd.oasis.opendocument.text" -> IncomingFormat.ODT
        else -> null
    }

    private fun readPrefix(file: File, limit: Int): ByteArray = RandomAccessFile(file, "r").use { input ->
        ByteArray(minOf(limit.toLong(), input.length()).toInt()).also(input::readFully)
    }
}
