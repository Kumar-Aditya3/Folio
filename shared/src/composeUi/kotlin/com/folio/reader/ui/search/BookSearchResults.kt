package com.folio.reader.ui.search

import com.folio.reader.ui.components.*
import androidx.compose.foundation.layout.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.ui.Alignment
import androidx.compose.material3.TextButton
import androidx.compose.ui.semantics.*
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Search
import androidx.compose.material3.ListItem
import androidx.compose.material3.ListItemDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset

/**
 * Search rows sit on the field, not on a plate of their own. `ListItem` otherwise
 * paints `ListItemDefaults.containerColor` = the palette `surface`, fully opaque, which
 * on a dark face laid a row of near-black bars over the animated ground — the same
 * "raw Material in a Folio screen" mismatch the menus had. Transparent lets the room
 * through and leaves the list's order to its type and its rules, which is how every
 * other list in the app is built. Precedent: TagManagerScreen's own list.
 */
private val searchRowColors: androidx.compose.material3.ListItemColors
    @Composable get() = ListItemDefaults.colors(containerColor = Color.Transparent)

/**
 * Renders FTS5 snippets: `<<term>>` becomes bold + accent instead of raw markers.
 */
@Composable
internal fun SnippetText(text: String, maxLines: Int) {
    // §12.3: a matched search term is a highlight on the text, so it borrows the
    // annotation/highlight hue rather than the app-wide primary.
    val highlight = FolioTheme.colors.accentAnnotation
    val annotated = remember(text, highlight) {
        buildAnnotatedString {
            var i = 0
            while (i < text.length) {
                val open = text.indexOf("<<", i)
                if (open < 0) { append(text.substring(i)); break }
                append(text.substring(i, open))
                val close = text.indexOf(">>", open + 2)
                if (close < 0) { append(text.substring(open)); break }
                withStyle(SpanStyle(fontWeight = FontWeight.Bold, color = highlight)) {
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
    var expandedKey by rememberSaveable(query, scope) { mutableStateOf<String?>(null) }
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
                            modifier = Modifier.padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
                        )
                    }
                    items(titleMatches, key = { "title:${it.id}" }) { book ->
                        SearchSourceRow(book, onClick = { onOpenTitle(book) })

                    }
                }

                SearchScope.CONTENT -> if (results.isNotEmpty()) {
                    item(key = "head:content") {
                        Text(
                            "Inside books",
                            style = FolioTheme.typography.titleSmall,
                            color = FolioTheme.colors.secondary,
                            modifier = Modifier.padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
                        )
                    }
                    items(results, key = { "hit:${it.book.id}:${it.spineIndex}:${it.context.hashCode()}" }) { hit ->
                        val key = "hit:${hit.book.id}:${hit.spineIndex}:${hit.context.hashCode()}"
                        SearchPassage(hit.book, hit.chapterTitle, hit.context, expandedKey == key,
                            onExpand = { expandedKey = if (expandedKey == key) null else key },
                            onOpen = { onOpenHit(hit) })

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
                                modifier = Modifier.padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)
                            )
                        }
                        items(
                            sectionHits,
                            key = { "${it.scope}:${it.book.id}:${it.title.hashCode()}:${it.snippet.hashCode()}" }
                        ) { hit ->
                            val key = "${hit.scope}:${hit.book.id}:${hit.title.hashCode()}:${hit.snippet.hashCode()}"
                            SearchPassage(hit.book, "${hit.scope.label} · ${hit.title}", hit.snippet, expandedKey == key,
                                onExpand = { expandedKey = if (expandedKey == key) null else key },
                                onOpen = { onOpenHit(BookHit(hit.book, hit.spineIndex ?: -1, hit.scope.label, hit.snippet)) })

                        }
                    }
                }
            }
        }
        if (query.isNotBlank() && !noStrongMatch && !searching &&
            titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()
        ) {
            item(key = "head:empty") {
                EmptyState(
                    icon = Icons.Filled.Search,
                    headline = "No matches for \"$query\"",
                    body = "Nothing in ${scope.label} matches your search — " +
                        "try another word, or a different scope.",
                    modifier = Modifier.fillMaxWidth(),
                )
            }
        }
        item(key = "tail") { Spacer(Modifier.height(24.dp)) }
    }
}

@Composable
private fun SearchSourceRow(book: Book, onClick: () -> Unit) {
    Row(Modifier.fillMaxWidth().clickable(onClick = onClick)
        .padding(horizontal = FolioTokens.gutter, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        FolioCoverPlate(book.coverPath, book.title, book.displayAuthor, width = 42.dp, small = true, suppressFallbackText = true)
        Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(3.dp)) {
            Text(book.title, style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface, maxLines = 2, overflow = TextOverflow.Ellipsis)
            Text(book.displayAuthor, style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant, maxLines = 1, overflow = TextOverflow.Ellipsis)
        }
    }
}

@Composable
internal fun SearchPassage(book: Book, chapter: String, snippet: String, expanded: Boolean,
    onExpand: () -> Unit, onOpen: () -> Unit) {
    val accent = FolioTheme.colors.accentAnnotation
    FolioPaperSheet(Modifier.padding(horizontal = FolioTokens.gutter, vertical = 6.dp), accent, layered = true) {
        Row(Modifier.fillMaxWidth().clickable(onClick = onExpand),
            verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(12.dp)) {
            FolioCoverPlate(book.coverPath, book.title, book.displayAuthor, width = 32.dp, small = true, suppressFallbackText = true)
            Column(Modifier.weight(1f)) {
                Text(book.title, style = FolioTheme.typography.titleSmall, color = FolioTheme.colors.onSurface,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
                Text(chapter, style = FolioTheme.typography.labelSmall, color = FolioTheme.colors.onSurfaceVariant,
                    maxLines = 2, overflow = TextOverflow.Ellipsis)
            }
        }
        Box(Modifier.fillMaxWidth().clickable(onClick = onExpand)
            .semantics { stateDescription = if (expanded) "Expanded passage" else "Collapsed passage" }) {
            SnippetText(snippet, maxLines = if (expanded) Int.MAX_VALUE else 3)
        }
        TextButton(onClick = onExpand) { Text(if (expanded) "Show less" else "Read more") }
        AnimatedVisibility(expanded) {
            FolioArtworkScene(FolioArtwork("book:${book.id}", book.coverPath), Modifier.fillMaxWidth()) {
                FolioActionDock("Open passage", chapter, onOpen, accent = accent,
                    accessibilityLabel = "Open passage in ${book.title}")
            }
        }
    }
}
