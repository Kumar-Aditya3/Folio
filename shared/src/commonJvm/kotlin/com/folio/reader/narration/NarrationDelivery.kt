package com.folio.reader.narration

/** Groups only within an original reader anchor. All positions remain source-text offsets. */
object NarrationDelivery {
    /** Pronunciations can expand text; keep even corrected input within the model's bound. */
    fun speechChunks(text: String): List<String> {
        val result = mutableListOf<String>()
        var start = 0
        while (start < text.length) {
            var end = minOf(start + 360,text.length)
            if (end < text.length) end = text.lastIndexOf(' ',end).takeIf { it > start } ?: end
            text.substring(start,end).trim().takeIf { it.isNotEmpty() }?.let(result::add)
            start = end
            while (text.getOrNull(start)?.isWhitespace() == true) start++
        }
        return result
    }
    fun plan(document: NarrationTextDocument, options: NarrationOptions): NarrationTextDocument {
        val result = mutableListOf<NarrationPassage>()
        for (passage in document.passages) {
            val previous = result.lastOrNull()
            if (options.paragraphPhrasing && previous != null && previous.blockId == passage.blockId &&
                previous.text.length + passage.text.length + 1 <= 360) {
                result[result.lastIndex] = previous.copy(text = previous.text + " " + passage.text,
                    end = passage.end, pauseAfterMs = passage.pauseAfterMs)
            } else result += passage
        }
        return document.copy(passages = if (options.structuralPauses) result else result.map { it.copy(pauseAfterMs = 0) })
    }

    /** Literal, case-insensitive, longest-first and non-cascading substitutions. */
    fun spoken(text: String, rules: List<PronunciationRule>): String {
        val valid = sanitize(rules).sortedByDescending { it.written.length }
        if (valid.isEmpty()) return text
        val lookup = valid.associate { it.written.lowercase(java.util.Locale.ROOT) to it.spoken }
        val pattern = Regex("(?<![\\p{L}\\p{N}_])(?:" + valid.joinToString("|") { Regex.escape(it.written) } + ")(?![\\p{L}\\p{N}_])", RegexOption.IGNORE_CASE)
        return pattern.replace(text) { lookup[it.value.lowercase(java.util.Locale.ROOT)] ?: it.value }
    }

    fun sanitize(rules: List<PronunciationRule>): List<PronunciationRule> = rules.asSequence()
        .map { PronunciationRule(it.written.trim().take(128), it.spoken.trim().take(256)) }
        .filter { it.written.isNotEmpty() && it.spoken.isNotEmpty() && !it.written.any(Char::isISOControl) && !it.spoken.any(Char::isISOControl) }
        .distinctBy { it.written.lowercase(java.util.Locale.ROOT) }.take(100).toList()
}
