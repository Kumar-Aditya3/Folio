package com.folio.reader.manga

/**
 * Recovers a chapter number from the chapter title when the source leaves
 * chapter_number unset (-1), mirroring Mihon's chapter recognition. Needed
 * because some sources list chapters newest-first and expose no number, so
 * the title is the only signal of a chapter's position in the story.
 */
object ChapterNumberParser {
    private val keywordNumber =
        Regex("""(?i)(?:ch(?:apter|ap)?|ep(?:isode)?|cap(?:itulo)?|chapitre)\.?\s*#?\s*(\d+(?:\.\d+)?)""")
    private val anyNumber = Regex("""\d+(?:\.\d+)?""")

    /** Returns the parsed number, or -1 when the title carries none. */
    fun parse(name: String): Float {
        keywordNumber.find(name)?.let { return it.groupValues[1].toFloatOrNull() ?: -1f }
        return anyNumber.findAll(name).lastOrNull()?.value?.toFloatOrNull() ?: -1f
    }
}
