package com.folio.reader.ui.render

sealed class LinkClickResult {
    data class ExternalUrl(val url: String) : LinkClickResult()
    data class InternalChapter(val chapterIndex: Int) : LinkClickResult()
    data class InlineContent(val href: String, val resolvedHref: String) : LinkClickResult()
}
