package com.folio.reader.ui.render

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.text.ParagraphStyle
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontStyle
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.BaselineShift
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextDecoration
import androidx.compose.ui.text.style.TextIndent
import androidx.compose.ui.unit.sp
import com.folio.reader.model.Chapter
import com.folio.reader.model.FormattingMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.ui.components.systemFontFamily
import org.xmlpull.v1.XmlPullParser
import org.xmlpull.v1.XmlPullParserFactory

/**
 * A rendered piece of a chapter: either a styled text paragraph or an image
 * reference (src relative to the chapter file, resolved by the platform layer).
 */
sealed class HtmlBlock {
    data class Text(val annotated: AnnotatedString) : HtmlBlock()
    data class Image(val src: String) : HtmlBlock()
}

/**
 * Renders EPUB chapter HTML into Compose [AnnotatedString]s using XmlPullParser.
 * Supports the common XHTML tag subset found in EPUBs (p, h1-h6, em, strong,
 * u, s, blockquote, code/pre, sup/sub, a, br, hr) plus <img> as [HtmlBlock.Image].
 */
class HtmlRenderer(
    private val settings: ReaderSettings,
    private val linkColor: Color = Color(0xFF1A73E8),
    private val onLinkClick: ((String) -> Unit)? = null,
    private val customFontFamilies: Map<String, String> = emptyMap()
) {
    private data class CssRule(
        val selector: String,
        val style: TextStyle,
        val hidden: Boolean,
        val textIndent: Float?
    )
    companion object {
        /** Tags that open a block-level paragraph (get one ParagraphStyle push). */
        private val BLOCK_TAGS = setOf(
            "p", "div", "section", "article", "header", "footer", "main", "aside",
            "h1", "h2", "h3", "h4", "h5", "h6", "blockquote", "pre", "figcaption", "center"
        )

        /** Structural container tags that should not propagate alignment to child paragraphs. */
        private val CONTAINER_TAGS = setOf(
            "div", "section", "article", "header", "footer", "main", "aside", "body", "html"
        )
    }

    private fun decodeHtmlEntities(html: String): String {
        var clean = html
            .replace("&nbsp;", " ")
            .replace("&apos;", "'")
            .replace("&quot;", "\"")
            .replace("&rsquo;", "’")
            .replace("&lsquo;", "‘")
            .replace("&ldquo;", "“")
            .replace("&rdquo;", "”")
            .replace("&mdash;", "—")
            .replace("&ndash;", "–")
            .replace("&hellip;", "…")
            .replace("&bull;", "•")
            .replace("&copy;", "©")
            .replace("&reg;", "®")
            .replace("&trade;", "™")
            .replace("&eacute;", "é")
            .replace("&egrave;", "è")
            .replace("&ecirc;", "ê")
            .replace("&agrave;", "à")
            .replace("&acirc;", "â")
            .replace("&ccedil;", "ç")
            .replace("&iuml;", "ï")
            .replace("&icirc;", "î")
            .replace("&ocirc;", "ô")
            .replace("&ugrave;", "ù")
            .replace("&ucirc;", "û")
            .replace("&uuml;", "ü")
            .replace("&ouml;", "ö")
            .replace("&auml;", "ä")
            .replace("&szlig;", "ß")
            .replace("&Eacute;", "É")
            .replace("&Agrave;", "À")
            .replace("&Ccedil;", "Ç")

        // Handle numeric decimal entities (e.g. &#39; &#8217;)
        clean = clean.replace(Regex("&#([0-9]+);")) { mr ->
            mr.groupValues[1].toIntOrNull()?.toChar()?.toString() ?: ""
        }
        // Handle numeric hex entities (e.g. &#x27; &#x2019;)
        clean = clean.replace(Regex("&#x([0-9a-fA-F]+);")) { mr ->
            mr.groupValues[1].toIntOrNull(16)?.toChar()?.toString() ?: ""
        }

        // Replace remaining non-standard HTML entities that break XmlPullParser
        clean = clean.replace(Regex("&([a-zA-Z0-9]+);")) { mr ->
            val entity = mr.groupValues[1]
            when (entity) {
                "amp", "lt", "gt", "quot", "apos" -> mr.value
                else -> " "
            }
        }

        // Escape raw '&' not part of valid XML entities
        clean = clean.replace(Regex("&(?!(amp|lt|gt|quot|apos);)")) {
            "&amp;"
        }

        return clean
    }

    /** Render into discrete blocks — text paragraphs and image placeholders. */
    fun renderToBlocks(html: String, baseStyle: TextStyle): List<HtmlBlock> {
        val blocks = mutableListOf<HtmlBlock>()
        val cleanHtml = decodeHtmlEntities(html)
        val cssRules = parseCssRules(cleanHtml)
        val parser = xmlFactory.newPullParser()
        parser.setInput(cleanHtml.reader())

        var para = AnnotatedString.Builder()
        val openTags = mutableListOf<OpenTag>()
        var skipDepth = 0
        var hiddenDepth = 0
        // Compose forbids overlapping (incl. NESTED) ParagraphStyles. EPUBs nest
        // blocks constantly (<div><p>…), so only the OUTERMOST active block pushes
        // a ParagraphStyle — inner blocks inherit it.
        var activeParaPushes = 0

        fun flushParagraph() {
            val text = para.toAnnotatedString()
            if (text.text.isNotBlank()) blocks.add(HtmlBlock.Text(text))
            // The builder (and any active paragraph style) is discarded — reset
            // accounting so later blocks know no style is active anymore.
            para = AnnotatedString.Builder()
            activeParaPushes = 0
            openTags.forEach { it.pushedPara = false }
        }

        fun emit(text: String) {
            if (text.isEmpty()) return
            para.append(text)
        }

        fun blockStyleFor(tag: String, publisherAlign: TextAlign?, inlineStyle: TextStyle? = null): TextStyle {
            val fs = settings.fontSize
            val readerAlign = when (settings.alignment) {
                com.folio.reader.settings.TextAlignment.JUSTIFIED -> TextAlign.Justify
                com.folio.reader.settings.TextAlignment.CENTER -> TextAlign.Center
                else -> TextAlign.Start
            }
            var style = TextStyle(
                fontFamily = bodyFamily(),
                fontWeight = FontWeight(settings.fontWeight),
                fontSize = fs.sp,
                lineHeight = (fs * settings.lineHeight).sp,
                letterSpacing = settings.letterSpacing.sp,
                textAlign = readerAlign
            )
            getTagStyle(tag)?.let { style = style.merge(it) }
            inlineStyle?.let { style = style.merge(it) }
            // NORMALIZED is the explicit "reader controls layout" mode. HYBRID
            // keeps publisher alignment only where it was actually declared.
            style = style.copy(
            textAlign = when {
                settings.formattingMode == FormattingMode.NORMALIZED -> readerAlign
                readerAlign == TextAlign.Justify -> TextAlign.Justify
                else ->                 publisherAlign ?: inlineStyle?.textAlign ?: readerAlign
                }
            )
            return style
        }

        try {
            var eventType = parser.eventType
            while (eventType != XmlPullParser.END_DOCUMENT) {
                when (eventType) {
                    XmlPullParser.START_TAG -> {
                        val name = parser.name.lowercase()
                        if (name == "script" || name == "style") {
                            skipDepth++
                        } else if (skipDepth == 0) {
                            val publisherAlign =
                                if (name in BLOCK_TAGS || name == "li" || name == "dd") parsePublisherAlign(parser) else null
                            val inlineStyle = parseInlineStyle(parser)
                            val classes = parser.getAttributeValue(null, "class")
                                ?.split(Regex("\\s+"))?.filter { it.isNotBlank() } ?: emptyList()
                            val id = parser.getAttributeValue(null, "id")
                            val matchingRules = cssRules.filter { matchesSelector(it.selector, name, classes, id) }
                            val selectorStyle = matchingRules.map { it.style }
                                .reduceOrNull { acc, style -> acc.merge(style) }
                            val effectiveInlineStyle = listOfNotNull(selectorStyle, inlineStyle)
                                .reduceOrNull { acc, style -> acc.merge(style) }
                            if (matchingRules.any { it.hidden } || isHidden(parser.getAttributeValue(null, "style")) || hiddenDepth > 0) {
                                if (hiddenDepth == 0) hiddenDepth++
                                openTags.add(OpenTag(name, isBlock = false, hidden = true))
                            } else when {
                                name == "br" -> emit("\n")
                                name == "hr" -> { emit("\n"); emit("â€”â€”â€”"); emit("\n") }
                                name == "img" -> {
                                    val src = parser.getAttributeValue(null, "src")
                                    if (!src.isNullOrBlank()) {
                                        flushParagraph()
                                        blocks.add(HtmlBlock.Image(src))
                                    }
                                }
                                name in BLOCK_TAGS || name == "li" || name == "dd" -> {
                                    // Paragraph styles cannot overlap in Compose. A nested block
                                    // (especially <div><h1>…</h1></div>) must therefore start a
                                    // new styled run instead of inheriting its container's style.
                                    // Keep the closest explicit publisher alignment while doing so.
                                    if (activeParaPushes > 0) flushParagraph()
                                    val inheritedAlign = openTags.lastOrNull {
                                        it.isBlock && it.publisherAlign != null
                                    }?.publisherAlign
                                    val effectiveAlign = when (settings.formattingMode) {
                                        FormattingMode.NORMALIZED -> null
                                        FormattingMode.HYBRID, FormattingMode.ORIGINAL ->
                                            publisherAlign ?: inheritedAlign
                                    }
                                    val indent = matchingRules.asSequence().mapNotNull { it.textIndent }.lastOrNull()
                                        ?: parseTextIndent(parser.getAttributeValue(null, "style"))
                                    val paragraph = blockStyleFor(name, effectiveAlign, effectiveInlineStyle).toParagraphStyle()
                                    para.pushStyle(
                                        if (indent != null) paragraph.copy(textIndent = TextIndent(firstLine = indent.sp))
                                        else paragraph
                                    )
                                    activeParaPushes++
                                    openTags.add(
                                        OpenTag(
                                            name,
                                            isBlock = true,
                                            pushedPara = true,
                                            publisherAlign = effectiveAlign,
                                            blockSpanStyle = getTagStyle(name)?.toSpanStyle()
                                        )
                                    )
                                    if (name == "li") emit(" • ")
                                    if (name == "dd") emit("    ")
                                }
                                name == "svg" -> {
                                    // SVG cover art / inline art: skip markup, mark a gap.
                                    skipDepth++
                                }
                                name == "audio" || name == "video" -> emit(" ")
                                name == "a" -> {
                                    val href = parser.getAttributeValue(null, "href") ?: ""
                                    para.pushStringAnnotation(tag = "url", annotation = href)
                                    openTags.add(
                                        OpenTag(
                                            "a", isBlock = false, annotationPushes = 1,
                                            pendingSpan = getTagStyle("a"), href = href
                                        )
                                    )
                                }
                                else -> {
                                    val style = getTagStyle(name)?.let { base ->
                                        effectiveInlineStyle?.let { base.merge(it) } ?: base
                                    } ?: effectiveInlineStyle
                                    if (style != null) {
                                        openTags.add(OpenTag(name, isBlock = false, pendingSpan = style))
                                    }
                                }
                            }
                        }
                    }
                    XmlPullParser.TEXT -> {
                        if (skipDepth == 0 && hiddenDepth == 0) {
                            val text = parser.text
                            if (!text.isBlank()) {
                                for (i in openTags.indices) {
                                    val t = openTags[i]
                                    val pending = t.pendingSpan
                                    if (pending != null) {
                                        para.pushStyle(pending.toSpanStyle())
                                        t.spanPushes++
                                        t.pendingSpan = null
                                    }
                                }
                                para.pushStyle(baseStyle.toSpanStyle())
                                val blockSpan = openTags.lastOrNull {
                                    it.isBlock && it.pushedPara
                                }?.blockSpanStyle
                                if (blockSpan != null) para.pushStyle(blockSpan)
                                emit(text)
                                if (blockSpan != null) para.pop()
                                para.pop()
                            }
                        }
                    }
                    XmlPullParser.END_TAG -> {
                        val name = parser.name.lowercase()
                        if (name == "svg" && skipDepth > 0) {
                            skipDepth--
                        } else if ((name == "script" || name == "style") && skipDepth > 0) {
                            skipDepth--
                        } else if (skipDepth == 0) {
                            val idx = openTags.indexOfLast { it.name == name }
                            if (idx != -1) {
                                while (openTags.size > idx + 1) {
                                    val top = openTags.removeAt(openTags.lastIndex)
                                    repeat(top.spanPushes) { runCatching { para.pop() } }
                                    repeat(top.annotationPushes) { runCatching { para.pop() } }
                                }
                                val matched = openTags.removeAt(openTags.lastIndex)
                                if (matched.isBlock && matched.pushedPara) {
                                    runCatching { para.pop() }
                                    activeParaPushes = (activeParaPushes - 1).coerceAtLeast(0)
                                }
                                repeat(matched.spanPushes) { runCatching { para.pop() } }
                                if (matched.hidden) hiddenDepth = (hiddenDepth - 1).coerceAtLeast(0)
                                repeat(matched.annotationPushes) { runCatching { para.pop() } }
                            }
                        }
                    }
                }
                eventType = parser.next()
            }
        } catch (_: Exception) {
            // Malformed HTML fragment: return what we have rendered so far.
        }
        flushParagraph()

        return blocks
    }

    fun renderToAnnotatedString(html: String, baseStyle: TextStyle): AnnotatedString {
        val builder = AnnotatedString.Builder()
        var first = true
        for (block in renderToBlocks(html, baseStyle)) {
            when (block) {
                is HtmlBlock.Text -> {
                    if (!first) builder.append("\n")
                    builder.append(block.annotated)
                    first = false
                }
                is HtmlBlock.Image -> Unit // images are rendered by the UI layer
            }
        }
        return builder.toAnnotatedString()
    }

    /** An open element with an exact count of builder entries to pop at close. */
    private class OpenTag(
        val name: String,
        val isBlock: Boolean,
        var spanPushes: Int = 0,
        var annotationPushes: Int = 0,
        var pendingSpan: TextStyle? = null,
        var pushedPara: Boolean = false,
        val href: String? = null,
        val publisherAlign: TextAlign? = null,
        val blockSpanStyle: SpanStyle? = null,
        val hidden: Boolean = false
    )

    private val xmlFactory: XmlPullParserFactory = XmlPullParserFactory.newInstance().apply {
        isNamespaceAware = true
        isValidating = false
    }

    private fun bodyFamily(): FontFamily {
        // Real system font first (desktop FontMgr resolves Georgia/Literata/etc.
        // with actual glyphs; Android maps to the closest system family).
        com.folio.reader.ui.components.systemFontFamily(settings.fontFamily)?.let { return it }
        val f = settings.fontFamily.lowercase()
        return when {
            f.contains("mono") || f.contains("jetbrains") || f.contains("code") -> FontFamily.Monospace
            f.contains("sans") || f.contains("inter") || f.contains("open sans") || f.contains("roboto") ||
                f.contains("poppins") || f.contains("nunito") || f.contains("raleway") ||
                f.contains("montserrat") || f.contains("ubuntu") || f.contains("fira") ||
                f.contains("work sans") || f.contains("space grotesk") || f.contains("source sans") -> FontFamily.Default
            else -> FontFamily.Serif // Literata, Merriweather, Georgia, Garamond, Lora, Times, PT Serif, Noto Serif, Crimson, Playfair, DM Serif, Baskerville.
        }
    }

    private fun parsePublisherAlign(parser: org.xmlpull.v1.XmlPullParser): TextAlign? {
        parser.getAttributeValue(null, "align")?.lowercase()?.let {
            if (it == "center") return TextAlign.Center
            if (it == "justify") return TextAlign.Justify
            if (it == "right" || it == "end") return TextAlign.End
        }
        parser.getAttributeValue(null, "style")?.lowercase()?.let { style ->
            val m = Regex("text-align\\s*:\\s*(center|justify|right|left|start|end)").find(style) ?: return@let null
            return when (m.groupValues[1]) {
                "center" -> TextAlign.Center
                "justify" -> TextAlign.Justify
                "right", "end" -> TextAlign.End
                else -> TextAlign.Start
            }
        }
        return null
    }

    private fun parseInlineStyle(parser: org.xmlpull.v1.XmlPullParser): TextStyle? {
        return parseStyleDeclarations(parser.getAttributeValue(null, "style"))
    }

    private fun parseStyleDeclarations(declarations: String?): TextStyle? {
        if (declarations.isNullOrBlank()) return null
        fun value(name: String) = Regex("(?i)(?:^|;)\\s*$name\\s*:\\s*([^;]+)").find(declarations)
            ?.groupValues?.getOrNull(1)?.trim()?.lowercase()
        var style = TextStyle()
        value("font-style")?.let { if (it == "italic" || it == "oblique") style = style.copy(fontStyle = FontStyle.Italic) }
        value("font-weight")?.let { if (it == "bold" || it.toIntOrNull()?.let { weight -> weight >= 600 } == true) style = style.copy(fontWeight = FontWeight.Bold) }
        value("text-decoration")?.let {
            if (it.contains("underline")) style = style.copy(textDecoration = TextDecoration.Underline)
            if (it.contains("line-through")) style = style.copy(textDecoration = TextDecoration.LineThrough)
        }
        value("text-align")?.let { align ->
            val textAlign = when (align) {
                "center" -> TextAlign.Center
                "right", "end" -> TextAlign.End
                "justify" -> TextAlign.Justify
                else -> TextAlign.Start
            }
            style = style.copy(textAlign = textAlign)
        }
        value("letter-spacing")?.removeSuffix("px")?.toFloatOrNull()?.let { style = style.copy(letterSpacing = it.sp) }
        value("font-size")?.let { size ->
            val number = size.removeSuffix("px").removeSuffix("pt").toFloatOrNull()
            if (number != null) style = style.copy(fontSize = number.sp)
        }
        value("line-height")?.let { height ->
            val number = height.removeSuffix("px").removeSuffix("pt").toFloatOrNull()
            if (number != null) style = style.copy(lineHeight = number.sp)
        }
        value("font-family")?.let { family ->
            if (family.contains("mono") || family.contains("code")) {
                style = style.copy(fontFamily = FontFamily.Monospace)
            } else if (family.contains("sans") || family.contains("arial") || family.contains("roboto")) {
                style = style.copy(fontFamily = FontFamily.Default)
            } else {
                style = style.copy(fontFamily = FontFamily.Serif)
            }
        }
        return style.takeIf {
            it.fontStyle != null || it.fontWeight != null || it.textDecoration != null ||
                it.letterSpacing != androidx.compose.ui.unit.TextUnit.Unspecified ||
                it.fontSize != androidx.compose.ui.unit.TextUnit.Unspecified ||
                it.textAlign != TextAlign.Unspecified ||
                it.lineHeight != androidx.compose.ui.unit.TextUnit.Unspecified ||
                value("font-family") != null
        }
    }

    private fun isHidden(declarations: String?): Boolean =
        declarations?.let { Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(it) } == true

    private fun parseTextIndent(declarations: String?): Float? =
        declarations?.let {
            Regex("(?i)text-indent\\s*:\\s*(-?[0-9.]+)\\s*(px|pt|em|rem)?").find(it)?.let { m ->
                val value = m.groupValues[1].toFloatOrNull() ?: return@let null
                if (m.groupValues[2].lowercase() == "em" || m.groupValues[2].lowercase() == "rem") {
                    value * settings.fontSize
                } else value
            }
        }

    private fun matchesSelector(selector: String, tag: String, classes: List<String>, id: String?): Boolean {
        val part = selector.trim().split(Regex("\\s+")).lastOrNull() ?: return false
        val wantedTag = part.substringBefore('.').substringBefore('#').substringBefore('[').lowercase()
        if (wantedTag.isNotBlank() && wantedTag != "*" && wantedTag != tag) return false
        Regex("#([\\w-]+)").find(part)?.groupValues?.get(1)?.let { if (it != id) return false }
        val wantedClasses = Regex("\\.([\\w-]+)").findAll(part).map { it.groupValues[1] }.toList()
        return classes.containsAll(wantedClasses)
    }

    private fun parseCssRules(html: String): List<CssRule> {
        val css = Regex("(?is)<style[^>]*>(.*?)</style>").findAll(html)
            .joinToString("\n") { it.groupValues[1] }
        if (css.isBlank()) return emptyList()
        return Regex("(?is)([^{}]+)\\{([^{}]*)\\}").findAll(css).flatMap { match ->
            val declarations = match.groupValues[2]
            val style = parseStyleDeclarations(declarations) ?: TextStyle()
            val hidden = Regex("(?i)(display\\s*:\\s*none|visibility\\s*:\\s*hidden)").containsMatchIn(declarations)
            val indent = Regex("(?i)text-indent\\s*:\\s*(-?[0-9.]+)\\s*(px|pt|em|rem)?")
                .find(declarations)?.let { it.groupValues[1].toFloatOrNull() }
            match.groupValues[1].split(',').asSequence()
                .map { CssRule(it.trim(), style, hidden, indent) }
        }.toList()
    }

    private fun getTagStyle(tagName: String): TextStyle? {
        val fs = settings.fontSize
        return when (tagName) {
            "center" -> TextStyle(textAlign = TextAlign.Center)
            "h1" -> TextStyle(fontWeight = FontWeight.Bold, fontSize = (fs * 2.0f).sp, lineHeight = (fs * 1.15f).sp)
            "h2" -> TextStyle(fontWeight = FontWeight.Bold, fontSize = (fs * 1.75f).sp, lineHeight = (fs * 1.15f).sp)
            "h3" -> TextStyle(fontWeight = FontWeight.Bold, fontSize = (fs * 1.5f).sp, lineHeight = (fs * 1.15f).sp)
            "h4" -> TextStyle(fontWeight = FontWeight.Bold, fontSize = (fs * 1.25f).sp, lineHeight = (fs * 1.15f).sp)
            "h5", "h6" -> TextStyle(fontWeight = FontWeight.Bold, fontSize = (fs * 1.1f).sp, lineHeight = (fs * 1.15f).sp)
            "b", "strong" -> TextStyle(fontWeight = FontWeight.Bold)
            "i", "em" -> TextStyle(fontStyle = FontStyle.Italic)
            "u" -> TextStyle(textDecoration = TextDecoration.Underline)
            "strike", "s", "del" -> TextStyle(textDecoration = TextDecoration.LineThrough)
            "blockquote" -> TextStyle(
                fontStyle = FontStyle.Italic,
                fontSize = fs.sp
            )
            "code", "pre" -> TextStyle(fontFamily = FontFamily.Monospace, fontSize = (fs * 0.9f).sp)
            "sup" -> TextStyle(fontSize = (fs * 0.7f).sp, baselineShift = BaselineShift.Superscript)
            "sub" -> TextStyle(fontSize = (fs * 0.7f).sp, baselineShift = BaselineShift.Subscript)
            "a" -> TextStyle(color = linkColor, textDecoration = TextDecoration.Underline)
            "small" -> TextStyle(fontSize = (fs * 0.8f).sp)
            "big" -> TextStyle(fontSize = (fs * 1.2f).sp)
            else -> null
        }
    }
}

/** Convenience: render a chapter's raw HTML body with current reader settings. */
@Composable
fun rememberHtmlRenderer(
    settings: ReaderSettings,
    onLinkClick: ((String) -> Unit)? = null,
    customFontFamilies: Map<String, String> = emptyMap()
): HtmlRenderer {
    val linkColor = androidx.compose.material3.MaterialTheme.colorScheme.primary
    return remember(settings, linkColor, onLinkClick, customFontFamilies) {
        HtmlRenderer(settings, linkColor, onLinkClick, customFontFamilies)
    }
}
