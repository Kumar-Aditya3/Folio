package com.folio.reader.ui.search

import androidx.compose.foundation.clickable
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
) {
    LazyColumn(state = listState, modifier = modifier) {
        if (titleMatches.isNotEmpty()) {
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
        if (results.isNotEmpty()) {
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
        if (annotationResults.isNotEmpty()) {
            item(key = "head:annotations") {
                Text(
                    "${scope.label} matches",
                    style = FolioTheme.typography.titleSmall,
                    color = FolioTheme.colors.secondary,
                    modifier = Modifier.padding(16.dp)
                )
            }
            items(
                annotationResults,
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
        if (query.isNotBlank() && titleMatches.isEmpty() && results.isEmpty() && annotationResults.isEmpty()) {
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
