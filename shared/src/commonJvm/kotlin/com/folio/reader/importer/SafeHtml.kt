package com.folio.reader.importer

import org.jsoup.Jsoup
import org.jsoup.nodes.Entities
import org.jsoup.safety.Cleaner
import org.jsoup.safety.Safelist
import java.io.File
import java.nio.charset.CodingErrorAction
import java.nio.charset.StandardCharsets
import java.nio.file.Files

internal object SafeHtml {
    private val safelist = Safelist.none()
        .addTags("html", "head", "body", "title", "meta", "p", "br", "hr", "h1", "h2", "h3", "h4", "h5", "h6",
            "ul", "ol", "li", "table", "thead", "tbody", "tfoot", "tr", "th", "td", "caption", "a", "img",
            "strong", "b", "em", "i", "u", "s", "blockquote", "pre", "code", "span", "div", "sup", "sub")
        .addAttributes("a", "href", "title")
        .addAttributes("img", "src", "alt", "title", "width", "height")
        .addAttributes("td", "colspan", "rowspan")
        .addAttributes("th", "colspan", "rowspan", "scope")
        .addAttributes("*", "id", "lang", "dir")

    fun sanitize(input: String, title: String, sourceDir: File? = null): SanitizedHtml {
        val dirty = Jsoup.parse(input, "")
        dirty.select("script,style,iframe,frame,object,embed,form,input,button,textarea,select,link,base,video,audio,source,svg,math").remove()
        val assets = linkedMapOf<String, ByteArray>()
        dirty.allElements.forEach { element ->
            element.attributes().asList().filter { it.key.startsWith("on", true) || it.key.equals("srcdoc", true) }
                .forEach { element.removeAttr(it.key) }
            if (element.hasAttr("href") && !safeUrl(element.attr("href"), allowAssets = false)) element.removeAttr("href")
            if (element.hasAttr("src")) {
                val src = element.attr("src")
                val asset = sourceDir?.let { readLocalAsset(it, src) }
                if (asset != null) {
                    val extension = asset.first.extension.lowercase()
                    val name = "image-${assets.size + 1}.$extension"
                    assets[name] = asset.second
                    element.attr("src", "assets/$name")
                } else if (!safeUrl(src, allowAssets = true)) {
                    element.removeAttr("src")
                }
            }
        }
        val clean = Cleaner(safelist).clean(dirty)
        clean.title(title)
        clean.outputSettings().escapeMode(Entities.EscapeMode.xhtml).prettyPrint(false)
        return SanitizedHtml(clean.outerHtml(), assets)
    }

    private fun safeUrl(value: String, allowAssets: Boolean): Boolean {
        val url = value.trim().replace("\\", "/")
        if (url.isEmpty() || url.startsWith('#')) return true
        if (url.startsWith("//") || url.startsWith('/') || Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(url)) return false
        return allowAssets && url.startsWith("assets/") && url.split('/').none { it == ".." }
    }

    private fun readLocalAsset(sourceDir: File, value: String): Pair<File, ByteArray>? {
        val relative = value.substringBefore('#').substringBefore('?').trim().replace('\\', '/')
        if (relative.isEmpty() || relative.startsWith('/') || relative.startsWith("//") ||
            Regex("^[A-Za-z][A-Za-z0-9+.-]*:").containsMatchIn(relative) || relative.split('/').any { it == ".." }) return null
        val file = File(sourceDir, relative)
        val rootPath = sourceDir.canonicalFile.toPath()
        val assetPath = file.canonicalFile.toPath()
        if (!assetPath.startsWith(rootPath) || !Files.isRegularFile(assetPath) || Files.isSymbolicLink(assetPath)) return null
        val extension = file.extension.lowercase()
        if (extension !in setOf("png", "jpg", "jpeg", "gif", "webp") || file.length() !in 1..(10L * 1024 * 1024)) return null
        return file to file.readBytes()
    }
}

internal data class SanitizedHtml(val html: String, val assets: Map<String, ByteArray>)

internal object TextProcessor {
    fun process(bytes: ByteArray, title: String): ProcessedDocument {
        val text = decode(bytes)
        if (text.indexOf('\u0000') >= 0) throw CorruptContent("Text input contains NUL bytes")
        val escaped = Entities.escape(text).replace("\r\n", "\n").replace('\r', '\n')
        val body = escaped.split(Regex("\n{2,}")).joinToString("\n") { paragraph ->
            "<p>${paragraph.replace("\n", "<br>")}</p>"
        }
        val sanitized = SafeHtml.sanitize("<body>$body</body>", title)
        return ProcessedDocument(title, sectionCount = 1, generatedHtml = sanitized.html)
    }

    private fun decode(bytes: ByteArray): String {
        if (bytes.startsWith(byteArrayOf(0xEF.toByte(), 0xBB.toByte(), 0xBF.toByte()))) return bytes.copyOfRange(3, bytes.size).toString(Charsets.UTF_8)
        if (bytes.startsWith(byteArrayOf(0xFF.toByte(), 0xFE.toByte()))) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16LE)
        if (bytes.startsWith(byteArrayOf(0xFE.toByte(), 0xFF.toByte()))) return bytes.copyOfRange(2, bytes.size).toString(Charsets.UTF_16BE)
        return try {
            StandardCharsets.UTF_8.newDecoder().onMalformedInput(CodingErrorAction.REPORT).decode(java.nio.ByteBuffer.wrap(bytes)).toString()
        } catch (_: Exception) {
            bytes.toString(Charsets.ISO_8859_1)
        }
    }

    private fun ByteArray.startsWith(prefix: ByteArray) = size >= prefix.size && prefix.indices.all { this[it] == prefix[it] }
}
