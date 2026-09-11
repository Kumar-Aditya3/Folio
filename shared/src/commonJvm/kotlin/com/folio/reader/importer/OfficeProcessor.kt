package com.folio.reader.importer

import org.xml.sax.Attributes
import org.xml.sax.SAXParseException
import org.xml.sax.helpers.DefaultHandler
import java.io.ByteArrayInputStream
import java.io.File
import java.io.RandomAccessFile
import java.util.zip.ZipException
import java.util.zip.ZipFile
import javax.xml.XMLConstants
import javax.xml.parsers.SAXParserFactory

internal object OfficeProcessor {
    fun process(file: File, format: IncomingFormat, fallbackTitle: String): ProcessedDocument {
        if (file.length() > MAX_OFFICE_BYTES) throw ContentTooLarge("Office document exceeds 100 MiB")
        try {
            ZipFile(file).use { zip ->
                validate(zip)
                val xmlName = if (format == IncomingFormat.DOCX) "word/document.xml" else "content.xml"
                val xml = readEntry(zip, xmlName)
                rejectDangerousXml(xml)
                val relationships = if (format == IncomingFormat.DOCX) readRelationships(zip) else emptyMap()
                val assets = linkedMapOf<String, ByteArray>()
                val handler = SemanticHandler(format, relationships, assets, zip)
                secureFactory().newSAXParser().parse(ByteArrayInputStream(xml), handler)
                val title = handler.title?.takeIf(String::isNotBlank) ?: fallbackTitle
                val sanitized = SafeHtml.sanitize("<body>${handler.html}</body>", title)
                if (sanitized.html.toByteArray().size > MAX_GENERATED_HTML_BYTES) throw ContentTooLarge("Generated HTML exceeds 50 MiB")
                return ProcessedDocument(title, sectionCount = handler.sections.coerceAtLeast(1), generatedHtml = sanitized.html, assets = assets)
            }
        } catch (e: ImportFailure) {
            throw e
        } catch (_: ZipException) {
            throw CorruptContent("Malformed office archive")
        } catch (e: Exception) {
            val message = e.message.orEmpty()
            if (e is UnsafeContent) throw e
            if (e is SAXParseException && (message.contains("doctype", true) || message.contains("entity", true) || message.contains("external", true))) {
                throw UnsafeContent("DTD and external entities are not allowed")
            }
            throw CorruptContent("Malformed office document: ${message.ifBlank { "invalid XML" }}")
        }
    }

    private fun validate(zip: ZipFile) {
        validateCentralDirectory(zip)
        var count = 0
        var total = 0L
        val entries = zip.entries()
        while (entries.hasMoreElements()) {
            val entry = entries.nextElement()
            if (++count > MAX_ARCHIVE_ENTRIES) throw UnsafeContent("Archive has too many entries")
            validateName(entry.name)
            if (entry.isDirectory) continue
            if (entry.method != java.util.zip.ZipEntry.STORED && entry.method != java.util.zip.ZipEntry.DEFLATED) {
                throw UnsafeContent("Unsupported archive entry method")
            }
            val size = entry.size
            val compressed = entry.compressedSize
            if (size < 0 || compressed < 0) throw UnsafeContent("Archive entry has unknown bounds")
            if (size > MAX_ARCHIVE_ENTRY_BYTES) throw ContentTooLarge("Archive entry exceeds 128 MiB")
            if ((size > 0 && compressed == 0L) || (compressed > 0 && size > compressed * MAX_COMPRESSION_RATIO)) {
                throw UnsafeContent("Suspicious archive compression ratio")
            }
            total += size
            if (total > MAX_ARCHIVE_BYTES) throw ContentTooLarge("Expanded archive exceeds 512 MiB")
        }
    }

    private fun validateCentralDirectory(zip: ZipFile) {
        RandomAccessFile(zip.name, "r").use { input ->
            val length = input.length()
            val tailSize = minOf(length, 65_557L).toInt()
            input.seek(length - tailSize)
            val tail = ByteArray(tailSize).also(input::readFully)
            var eocd = -1
            for (index in tail.size - 22 downTo 0) {
                if (tail[index] == 0x50.toByte() && tail[index + 1] == 0x4b.toByte() &&
                    tail[index + 2] == 0x05.toByte() && tail[index + 3] == 0x06.toByte()) {
                    eocd = index
                    break
                }
            }
            if (eocd < 0) throw CorruptContent("Archive central directory is missing")
            val centralSize = uint32(tail, eocd + 12)
            val centralOffset = uint32(tail, eocd + 16)
            if (centralOffset + centralSize > length) throw CorruptContent("Archive central directory is invalid")
            input.seek(centralOffset)
            val header = ByteArray(46)
            var consumed = 0L
            while (consumed < centralSize) {
                input.readFully(header)
                if (uint32(header, 0) != 0x02014b50L) throw CorruptContent("Malformed archive central directory")
                val madeBy = ushort(header, 4)
                val externalAttributes = uint32(header, 38)
                val unixMode = (externalAttributes ushr 16).toInt()
                if ((madeBy ushr 8) == 3 && (unixMode and 0xF000) == 0xA000) {
                    throw UnsafeContent("Archive contains a symbolic link")
                }
                val nameLength = ushort(header, 28)
                val extraLength = ushort(header, 30)
                val commentLength = ushort(header, 32)
                val skip = nameLength.toLong() + extraLength + commentLength
                input.seek(input.filePointer + skip)
                consumed += header.size + skip
            }
            if (consumed != centralSize) throw CorruptContent("Malformed archive central directory")
        }
    }

    private fun ushort(bytes: ByteArray, offset: Int): Int =
        (bytes[offset].toInt() and 0xff) or ((bytes[offset + 1].toInt() and 0xff) shl 8)

    private fun uint32(bytes: ByteArray, offset: Int): Long =
        ushort(bytes, offset).toLong() or (ushort(bytes, offset + 2).toLong() shl 16)

    private fun validateName(name: String) {
        val normalized = name.replace('\\', '/')
        if (normalized.startsWith('/') || Regex("^[A-Za-z]:").containsMatchIn(normalized) ||
            normalized.split('/').any { it == ".." } || name.any { it.code < 32 }) {
            throw UnsafeContent("Unsafe archive entry path")
        }
    }

    private fun readEntry(zip: ZipFile, name: String): ByteArray {
        val entry = zip.getEntry(name) ?: throw CorruptContent("Required archive entry is missing: $name")
        return zip.getInputStream(entry).use { readBounded(it, MAX_ARCHIVE_ENTRY_BYTES) }
    }

    private fun readBounded(input: java.io.InputStream, limit: Long): ByteArray {
        val output = java.io.ByteArrayOutputStream()
        val buffer = ByteArray(8192)
        var total = 0L
        while (true) {
            val read = input.read(buffer)
            if (read < 0) break
            total += read
            if (total > limit) throw ContentTooLarge("Archive entry exceeds its limit")
            output.write(buffer, 0, read)
        }
        return output.toByteArray()
    }

    private fun rejectDangerousXml(bytes: ByteArray) {
        val xml = bytes.toString(Charsets.ISO_8859_1)
        if (Regex("(?is)<!ENTITY\\b|<!DOCTYPE\\b[^>]*(?:SYSTEM|PUBLIC)\\s+['\"]").containsMatchIn(xml)) {
            throw UnsafeContent("DTD and external entities are not allowed")
        }
    }

    private fun secureFactory() = SAXParserFactory.newInstance().apply {
        isNamespaceAware = true
        setFeatureIfSupported("http://apache.org/xml/features/disallow-doctype-decl", false)
        setFeatureIfSupported("http://xml.org/sax/features/external-general-entities", false)
        setFeatureIfSupported("http://xml.org/sax/features/external-parameter-entities", false)
        setFeatureIfSupported("http://apache.org/xml/features/nonvalidating/load-external-dtd", false)
        setFeatureIfSupported(XMLConstants.FEATURE_SECURE_PROCESSING, true)
    }

    private fun SAXParserFactory.setFeatureIfSupported(name: String, enabled: Boolean) {
        try {
            setFeature(name, enabled)
        } catch (_: org.xml.sax.SAXNotRecognizedException) {
        } catch (_: org.xml.sax.SAXNotSupportedException) {
        }
    }

    private fun readRelationships(zip: ZipFile): Map<String, String> {
        val entry = zip.getEntry("word/_rels/document.xml.rels") ?: return emptyMap()
        val bytes = zip.getInputStream(entry).use { readBounded(it, MAX_ARCHIVE_ENTRY_BYTES) }
        rejectDangerousXml(bytes)
        val result = mutableMapOf<String, String>()
        secureFactory().newSAXParser().parse(ByteArrayInputStream(bytes), object : DefaultHandler() {
            override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
                if ((localName ?: qName)?.substringAfter(':') == "Relationship") {
                    val target = attributes.getValue("Target") ?: return
                    if (safeLink(target)) result[attributes.getValue("Id")] = target
                }
            }
        })
        return result
    }

    private fun safeLink(value: String): Boolean = !value.startsWith('/') && !value.startsWith("//") &&
        !Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(value) && !value.replace('\\', '/').split('/').contains("..")

    private fun resolveArchivePath(base: String, relative: String): String? {
        val normalized = relative.replace('\\', '/')
        if (normalized.startsWith('/') || normalized.startsWith("//") || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(normalized)) return null
        val parts = ArrayDeque<String>()
        (base.trimEnd('/') + "/" + normalized).split('/').forEach { part ->
            when (part) {
                "", "." -> Unit
                ".." -> if (parts.isEmpty()) return null else parts.removeLast()
                else -> parts.addLast(part)
            }
        }
        return parts.joinToString("/")
    }

    private class SemanticHandler(
        private val format: IncomingFormat,
        private val relationships: Map<String, String>,
        private val assets: MutableMap<String, ByteArray>,
        private val zip: ZipFile
    ) : DefaultHandler() {
        val html = StringBuilder()
        var sections = 0
        var title: String? = null
        private val text = StringBuilder()
        private val rendered = StringBuilder()
        private var paragraphTag = "p"
        private var inParagraph = false
        private var hyperlink: String? = null
        private var listParagraph = false

        override fun startElement(uri: String?, localName: String?, qName: String?, attributes: Attributes) {
            val name = (localName ?: qName ?: "").substringAfter(':')
            when {
                format == IncomingFormat.DOCX && name == "p" -> beginParagraph("p")
                format == IncomingFormat.DOCX && name == "pStyle" -> {
                    Regex("(?i)heading([1-6])").find(attr(attributes, "val") ?: "")?.groupValues?.get(1)?.let {
                        paragraphTag = "h$it"; sections++
                    }
                }
                format == IncomingFormat.DOCX && name == "numPr" -> listParagraph = true
                format == IncomingFormat.DOCX && name == "tbl" -> html.append("<table><tbody>")
                format == IncomingFormat.DOCX && name == "tr" -> html.append("<tr>")
                format == IncomingFormat.DOCX && name == "tc" -> html.append("<td>")
                format == IncomingFormat.DOCX && name == "hyperlink" -> attr(attributes, "id")?.let(relationships::get)?.takeIf(::safeLink)?.let {
                    hyperlink = it
                    if (inParagraph) rendered.append("<a href=\"${org.jsoup.nodes.Entities.escape(it)}\">")
                }
                format == IncomingFormat.DOCX && name == "blip" -> attr(attributes, "embed")?.let(relationships::get)?.let {
                    val resolved = resolveArchivePath("word", it)
                    if (resolved != null) addImage(resolved)
                }
                format == IncomingFormat.ODT && name == "h" -> {
                    val level = (attr(attributes, "outline-level")?.toIntOrNull() ?: 1).coerceIn(1, 6)
                    beginParagraph("h$level"); sections++
                }
                format == IncomingFormat.ODT && name == "p" -> beginParagraph("p")
                format == IncomingFormat.ODT && name == "list" -> html.append("<ul>")
                format == IncomingFormat.ODT && name == "list-item" -> html.append("<li>")
                format == IncomingFormat.ODT && name == "table" -> html.append("<table><tbody>")
                format == IncomingFormat.ODT && name == "table-row" -> html.append("<tr>")
                format == IncomingFormat.ODT && name == "table-cell" -> html.append("<td>")
                format == IncomingFormat.ODT && name == "a" -> attr(attributes, "href")?.takeIf(::safeLink)?.let {
                    hyperlink = it
                    if (inParagraph) rendered.append("<a href=\"${org.jsoup.nodes.Entities.escape(it)}\">")
                }
                format == IncomingFormat.ODT && name == "image" -> attr(attributes, "href")?.takeIf(::safeLink)?.let(::addImage)
            }
        }

        override fun characters(ch: CharArray, start: Int, length: Int) {
            if (inParagraph) {
                text.append(ch, start, length)
                rendered.append(org.jsoup.nodes.Entities.escape(String(ch, start, length)))
            }
        }

        override fun endElement(uri: String?, localName: String?, qName: String?) {
            val name = (localName ?: qName ?: "").substringAfter(':')
            when {
                format == IncomingFormat.DOCX && name == "p" -> finishParagraph()
                format == IncomingFormat.DOCX && name == "tbl" -> html.append("</tbody></table>")
                format == IncomingFormat.DOCX && name == "tr" -> html.append("</tr>")
                format == IncomingFormat.DOCX && name == "tc" -> html.append("</td>")
                format == IncomingFormat.DOCX && name == "hyperlink" -> {
                    if (hyperlink != null && inParagraph) rendered.append("</a>")
                    hyperlink = null
                }
                format == IncomingFormat.ODT && (name == "p" || name == "h") -> finishParagraph()
                format == IncomingFormat.ODT && name == "list" -> html.append("</ul>")
                format == IncomingFormat.ODT && name == "list-item" -> html.append("</li>")
                format == IncomingFormat.ODT && name == "table" -> html.append("</tbody></table>")
                format == IncomingFormat.ODT && name == "table-row" -> html.append("</tr>")
                format == IncomingFormat.ODT && name == "table-cell" -> html.append("</td>")
                format == IncomingFormat.ODT && name == "a" -> {
                    if (hyperlink != null && inParagraph) rendered.append("</a>")
                    hyperlink = null
                }
            }
        }

        private fun beginParagraph(tag: String) {
            inParagraph = true
            paragraphTag = tag
            text.clear()
            rendered.clear()
            listParagraph = false
        }

        private fun finishParagraph() {
            val plain = text.toString().trim()
            if (plain.isNotEmpty()) {
                val body = rendered.toString().trim()
                if (listParagraph) html.append("<ul><li>$body</li></ul>") else html.append("<$paragraphTag>$body</$paragraphTag>")
                if (title == null && paragraphTag.startsWith('h')) title = plain
            }
            text.clear(); rendered.clear(); inParagraph = false; paragraphTag = "p"; listParagraph = false
        }

        private fun addImage(path: String) {
            val entry = zip.getEntry(path) ?: return
            if (entry.size !in 1..(10L * 1024 * 1024)) return
            val extension = path.substringAfterLast('.', "").lowercase()
            if (extension !in setOf("png", "jpg", "jpeg", "gif", "webp")) return
            val name = "image-${assets.size + 1}.$extension"
            assets[name] = zip.getInputStream(entry).use { readBounded(it, 10L * 1024 * 1024) }
            html.append("<img src=\"assets/$name\" alt=\"\">")
        }

        private fun attr(attributes: Attributes, local: String): String? {
            for (i in 0 until attributes.length) {
                if (attributes.getLocalName(i) == local || attributes.getQName(i).substringAfter(':') == local) return attributes.getValue(i)
            }
            return null
        }
    }
}
