package com.folio.reader.model

/** A source passage, rather than an ordinary Resume request. Kept intact across platform navigation. */
data class ReaderPassageTarget(
    val bookId: String,
    val spineIndex: Int? = null,
    val locator: String? = null,
    val highlightId: String? = null,
)
