package com.folio.reader.ui.search

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.ListItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset

/**
 * Renders FTS5 snippets: `<<term>>` becomes bold + accent instead of raw markers.
 */
@Composable
internal fun SnippetText(text: String, maxLines: Int) {
    val primary = FolioTheme.colors.primary
    val annotated = remember(text) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val open = text.indexOf("<<", i)
                if (open < 0) { append(text.substring(i)); break }
                append(text.substring(i, open))
                val close = text.indexOf(">>", open + 2)
                if (close < 0) { append(text.substring(open)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = primary)) {
                    append(text.substring(open + 2, close))
                }
                i = close + 2
            }
        }
    }
    Text(annotated, maxLines = maxLines, overflow = TextOverflow.Ellipsis)
}

/**
 * The search result list shared by the full-screen search (opened from the
 * reader) and the library rail search: title matches, inside-book hits,
 * annotation matches and the honest empty state, rendered identically wherever
 * results are shown so the two surfaces differ only in where the field lives.
 *
 * [onOpenTitle] receives title/author matches (they open the book's detail);
 * [onOpenHit] receives content and annotation hits (they open the reader at the
 * hit's spine).
 *
 * The library rail search floats this list under its masthead, so the first
 * rows pad by [LocalFolioTopInset] to clear the glass — without it the section
 * headers and opening hits sat behind the bar. The pushed search screen has no
 * floating bar, and the locals default to zero there.
 */
@Composable
fun BookSearchResultsList(
    query: String,
    scope: SearchScope,
    titleMatches: List<Book>,
    results: List<BookHit>,
    annotationResults: List<AnnotationHit>,
    onOpenTitle: (Book) -> Unit,
    onOpenHit: (BookHit) -> Unit,
    modifier: Modifier = Modifier,
    listState: LazyListState = rememberLazyListState(),
    /**
     * The semantic path answered and nothing cleared the relevance floor.
     *
     * Suppresses this list's own "No matches for …" line, which would be the wrong sentence:
     * the reader chose a meaning-based mode, the library may well contain the words, and the
     * search screen already shows the explanation above the list. Two messages saying
     * different things about one empty result is worse than one, and the more specific one
     * wins.
     */
    noStrongMatch: Boolean = false,
    /**
     * A search is in flight (debouncing, embedding, scanning, or fanning out over books).
     *
     * Suppresses the "No matches for …" line while it runs. Results are empty *before* a search
     * completes just as they are when it genuinely found nothing, and showing the empty sentence
     * during the in-flight window flashed "No matches" under the spinner on every keystroke. The
     * empty state is only honest once the work has finished and the results are still empty.
     */
    searching: Boolean = false,
) {
    LazyColumn(
        state = listState,
        modifier = modifier,
        contentPadding = PaddingValues(
            top = LocalFolioTopInset.current,
            bottom = LocalFolioBarInset.current,
        ),
    ) {
        // Sections follow the scope chips' own order — Titles, Content, Highlights, Notes,
        // Bookmarks — so the list reads top-to-bottom in the same sequence as the row of
        // chips above it, and the first section under the field is the scope the reader is
        // actually in.
        //
        // The order is *derived* from `SearchScope.entries` rather than hardcoded as
        // Titles → Content → annotations. The hardcoded version had two problems: an
        // annotation scope could never sort before the content section no matter which chip
        // was selected, and adding a scope to the enum would silently leave it out here. A
        // lookup keyed on the enum cannot drift from the chips.
        //
        // Within the annotation section the entries are ordered by the scope the hit came
        // from, for the same reason: a list mixing highlights and notes should group in the
        // chips' order rather than in whatever order the per-book fan-out happened to append.
        SearchScope.entries.forEach { section ->
            when (section) {
                SearchScope.TITLES -> if (titleMatches.isNotEmpty()) {
                    item(key = "head:titles") {
                        Text(
                            "Titles & authors",
                            style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.secondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(titleMatches, key = { "title:${it.id}" }) { book ->
                        ListItem(
                            headlineContent = { Text(book.title, maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { Text(book.displayAuthor, maxLines = 1) },
                            modifier = Modifier.fillMaxWidth().clickable { onOpenTitle(book) }
                        )
                    }
                }

                SearchScope.CONTENT -> if (results.isNotEmpty()) {
                    item(key = "head:content") {
                        Text(
                            "Inside books",
                            style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.secondary,
                            modifier = Modifier.padding(16.dp)
                        )
                    }
                    items(results, key = { "hit:${it.book.id}:${it.spineIndex}:${it.context.hashCode()}" }) { hit ->
                        ListItem(
                            headlineContent = { Text("${hit.book.title} - ${hit.chapterTitle}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                            supportingContent = { SnippetText(hit.context, maxLines = 2) },
                            modifier = Modifier.fillMaxWidth().clickable { onOpenHit(hit) }
                        )
                    }
                }

                else -> {
                    // Highlights / Notes / Bookmarks — the annotation scopes, each its own
                    // section in chip order.
                    val sectionHits = annotationResults.filter { it.scope == section }
                    if (sectionHits.isNotEmpty()) {
                        item(key = "head:${section.name}") {
                            Text(
                                "${section.label} matches",
                                style = FolioTheme.typography.titleSmall,
                                color = FolioTheme.colors.secondary,
                                modifier = Modifier.padding(16.dp)
                            )
                        }
                        items(
                            sectionHits,
                            key = { "${it.scope}:${it.book.id}:${it.title.hashCode()}:${it.snippet.hashCode()}" }
                        ) { hit ->
                            ListItem(
                                headlineContent = { Text("${hit.book.title} · ${hit.title}", maxLines = 1, overflow = TextOverflow.Ellipsis) },
                                supportingContent = { Text(hit.snippet, maxLines = 2, overflow = TextOverflow.Ellipsis) },
                                modifier = Modifier
                                    .fillMaxWidth()
                                    .clickable { onOpenHit(BookHit(hit.book, hit.spineIndex ?: -1, hit.scope.label, hit.snippet)) }
                            )
                        }
                    }
                }
            }
        }
        if (query.isNotBlank() && !noStrongMatch && !searching &&
            titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()
        ) {
            item(key = "head:empty") {
                Text(
                    "No matches for \"$query\" in ${scope.label}.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                    modifier = Modifier.padding(16.dp)
                )
            }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}
