package com.folio.reader.epub

import com.folio.reader.model.ParsedEpub

sealed class EpubParseResult {
    data class Success(val epub: ParsedEpub) : EpubParseResult()
    data class PartialSuccess(
        val epub: ParsedEpub,
        val parseWarnings: List<String>
    ) : EpubParseResult()
    data class Failure(val error: String, val cause: Throwable? = null) : EpubParseResult()

    val isSuccess: Boolean get() = this is Success || this is PartialSuccess
    val epubOrNull: ParsedEpub? get() = when (this) {
        is Success -> epub
        is PartialSuccess -> epub
        is Failure -> null
    }
    val allWarnings: List<String> get() = when (this) {
        is Success -> emptyList()
        is PartialSuccess -> parseWarnings
        is Failure -> listOf(error)
    }
}
