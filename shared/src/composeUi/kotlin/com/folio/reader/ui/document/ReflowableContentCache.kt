package com.folio.reader.ui.document

import com.folio.reader.ui.render.ReaderSection

/**
 * The part of a reflowable document visit that costs seconds: the generated
 * `index.html` and the chunks cut from it. Both are pure functions of the stored
 * bytes, so a warm open renders byte-identically to a cold one.
 */
internal data class ReflowableContent(
    val html: String,
    val chunks: List<ReaderSection>
)

/**
 * Identity of the exact bytes a [ReflowableContent] was built from. [contentHash] is
 * the digest of the stored original and [indexBytes] the length of the generated file,
 * so a re-import that changed the content can never read back as a hit.
 */
internal data class ReflowableContentKey(
    val documentId: String,
    val contentHash: String,
    val indexBytes: Long
)

private const val REFLOWABLE_CACHE_MAX_BYTES = 32L * 1024L * 1024L

/**
 * Process-scoped because the reader's view model is rebuilt for every visit, so with
 * nothing shared across visits each return to the same document re-reads the whole
 * file and re-runs the Jsoup chunk pass over it.
 */
internal val reflowableContentCache = PixelBudgetLruCache<ReflowableContentKey, ReflowableContent>(
    maxBytes = REFLOWABLE_CACHE_MAX_BYTES
) { content ->
    // Strings are UTF-16 here, and the chunker copies each chunk out of the source
    // document, so both halves of the pair stay resident.
    (content.html.length.toLong() + content.chunks.sumOf { it.html.length }) * 2L
}
