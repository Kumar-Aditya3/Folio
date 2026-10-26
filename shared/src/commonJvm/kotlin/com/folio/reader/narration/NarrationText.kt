package com.folio.reader.narration

import org.jsoup.Jsoup
import org.jsoup.nodes.Element
import org.jsoup.nodes.TextNode
import java.security.MessageDigest
import java.text.BreakIterator
import java.util.Locale

data class NarrationPassage(val blockId: String, val paragraph: Int, val text: String,
    val start: Int, val end: Int, val chapterLength: Int) {
    val hash: String get() = MessageDigest.getInstance("SHA-256").digest(text.toByteArray())
        .joinToString("") { "%02x".format(it) }
    val words: Long get() = Regex("\\S+").findAll(text).count().toLong()
}
data class NarrationTextDocument(val html: String, val passages: List<NarrationPassage>, val length: Int)

/** The source parser and displayed EPUB use this exact same deterministic annotation pass. */
object NarrationText {
    private val blocks = setOf("p", "h1", "h2", "h3", "h4", "h5", "h6", "li", "div", "blockquote", "pre", "td", "figcaption")
    private val excluded = setOf("script", "style", "nav", "noscript", "rt", "rp", "template")
    fun prepare(html: String): NarrationTextDocument {
        val document = Jsoup.parse(html)
        document.outputSettings().prettyPrint(false)
        val body = document.body()
        val owners = linkedMapOf<Element, StringBuilder>()
        fun walk(el: Element, hidden: Boolean = false) {
            val omit = hidden || el.normalName() in excluded || el.hasAttr("hidden") || el.attr("aria-hidden") == "true" ||
                Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(el.attr("style")) ||
                el.attr("epub:type").split(' ').any { it in setOf("footnote", "endnote", "toc") }
            if (omit) return
            for (node in el.childNodes()) when (node) {
                is TextNode -> {
                    var owner = el
                    while (owner !== body && owner.normalName() !in blocks) owner = owner.parent() ?: body
                    owners.getOrPut(owner) { StringBuilder() }.append(node.wholeText)
                }
                is Element -> if (node.normalName() == "br") {
                    var owner = el
                    while (owner !== body && owner.normalName() !in blocks) owner = owner.parent() ?: body
                    owners.getOrPut(owner) { StringBuilder() }.append(' ')
                } else walk(node, omit)
            }
        }
        walk(body)
        // Parent text surrounding nested blocks cannot be narrated in correct order as one block.
        // Give each text run its own inline anchor instead of moving publisher content.
        if (owners.keys.any { owner -> owners.keys.any { it !== owner && it.parents().contains(owner) } }) {
            val runs = mutableListOf<Pair<Element, StringBuilder>>()
            var runOwner: Element? = null
            fun mark(el: Element, hidden: Boolean = false) {
                val omit = hidden || el.normalName() in excluded || el.hasAttr("hidden") || el.attr("aria-hidden") == "true" ||
                    Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(el.attr("style")) ||
                    el.attr("epub:type").split(' ').any { it in setOf("footnote", "endnote", "toc") }
                if (omit) return
                for (node in el.childNodes().toList()) when (node) {
                    is TextNode -> {
                        var owner = el
                        while (owner !== body && owner.normalName() !in blocks) owner = owner.parent() ?: body
                        if (owner === runOwner && runs.isNotEmpty()) runs.last().second.append(node.wholeText)
                        else if (node.wholeText.isNotBlank()) {
                            val span = Element("span"); node.replaceWith(span); span.appendChild(node)
                            runs += span to StringBuilder(node.wholeText)
                            runOwner = owner
                        }
                    }
                    is Element -> if (node.normalName() == "br" && runs.isNotEmpty()) runs.last().second.append(' ') else mark(node, omit)
                }
            }
            mark(body)
            return build(document.outerHtml(), runs.map { it.first to it.second.toString() }, document)
        }
        return build(html, owners.map { it.key to it.value.toString() }, document)
    }

    private fun build(original: String, runs: List<Pair<Element, String>>, doc: org.jsoup.nodes.Document): NarrationTextDocument {
        val pieces = mutableListOf<NarrationPassage>()
        var offset = 0
        val paragraphIndices = doc.body().select("p").withIndex().associate { it.value to it.index }
        for ((index, pair) in runs.withIndex()) {
            val (el, raw) = pair
            val text = raw.replace('\u00a0', ' ').replace(Regex("\\s+"), " ").trim()
            if (text.isEmpty()) continue
            val id = "b$index"
            el.attr("data-folio-narration", id)
            val paragraph = (sequenceOf(el) + el.parents().asSequence()).firstNotNullOfOrNull { paragraphIndices[it] } ?: -1
            val boundaries = BreakIterator.getSentenceInstance(Locale.ENGLISH).apply { setText(text) }
            var start = boundaries.first()
            var end = boundaries.next()
            while (end != BreakIterator.DONE) {
                val sentence = text.substring(start, end)
                // Bound pathological sentences at whitespace; keep every character once.
                var local = 0
                while (local < sentence.length) {
                    var cut = minOf(local + 360, sentence.length)
                    if (cut < sentence.length) cut = sentence.lastIndexOf(' ', cut).takeIf { it > local } ?: cut
                    val spoken = sentence.substring(local, cut).trim()
                    if (spoken.isNotEmpty()) pieces += NarrationPassage(id, paragraph, spoken,
                        offset + start + local, offset + start + cut, 0)
                    local = cut
                }
                start = end; end = boundaries.next()
            }
            offset += text.length + 1
        }
        return NarrationTextDocument(if (pieces.isEmpty()) original else doc.outerHtml(),
            pieces.map { it.copy(chapterLength = offset.coerceAtLeast(1)) }, offset)
    }
}
