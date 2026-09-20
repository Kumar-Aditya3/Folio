@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.book

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.AssistChip
import androidx.compose.material3.AssistChipDefaults
import androidx.compose.material3.SuggestionChip
import androidx.compose.material3.SuggestionChipDefaults
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Collection
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.readingTimeCaption
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * Book detail, opened as an **editorial title page**.
 *
 * The cover throws a halo onto the page and its dominant colour tints the author
 * line and the progress figure, so the book's own artwork sets the temperature of
 * its own screen. Title runs at `headlineLarge` beside the plate rather than under
 * it; progress is a figure plus a seam, not a ring plus two labels.
 *
 * Metadata drops into a sunken well below — reference material sits *in* the page,
 * not on another card.
 */
@Composable
internal fun BookHeaderSection(
    book: Book,
    series: Series?,
    collections: List<Collection>,
    tags: List<Tag>,
    sessionsCount: Int,
    wordsRead: Long,
    highlightsCount: Int,
    bookmarksCount: Int,
    notesCount: Int,
    onTagClick: (Tag) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onCollectionClick: (Collection) -> Unit,
    onAddTags: () -> Unit,
    /**
     * Phase 5 #2's entry point. Null when the build has no auto-tagger wired, in which case
     * the chip is not rendered at all — an affordance that cannot do anything is worse than
     * an absent one.
     */
    onSuggestTags: (() -> Unit)? = null,
    onCoverClick: () -> Unit = {},
    /**
     * This book's reading sessions, used to project a finish from the reader's own
     * measured pace. Empty (the default) withholds the projection rather than
     * inventing one — see [remainingTimeCaption].
     */
    sessions: List<ReadingSession> = emptyList(),
) {
    val accent = rememberCoverAccent(book.coverPath, FolioTheme.colors.accentProgress)
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.gutter),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space4),
            verticalAlignment = Alignment.Top
        ) {
            FolioCoverPlate(
                coverPath = book.coverPath,
                title = book.title,
                author = book.displayAuthor,
                width = FolioTokens.coverFeature,
                halo = accent,
                elevation = 16.dp,
                // The title and author beside this plate carry the paired keys and
                // fly in with it, so the fallback must not draw its own copies —
                // they would sit at a fourth size in the same row.
                suppressFallbackText = true,
                modifier = Modifier
                    // §17: the same plate the shelf handed over — matching key,
                    // so the cover flies from the grid cell into this page.
                    //
                    // `contentSize` because this is a *destination across two
                    // different tokens*: Home's anchor is `coverAnchor` (148dp) and
                    // this header's plate is `coverFeature` (112dp). Under the API
                    // default the placeholder follows the interpolated bounds while
                    // the cover drawn inside keeps its own size, so the two agree
                    // only on the final frame and the cover visibly jumps into place
                    // as the flight ends. `contentSize` makes the artwork the size
                    // of the box around it for the whole morph. From the library
                    // grid, whose source plate is also `coverFeature`, there is no
                    // size change and either mode is identical.
                    .sharedElementOrNoop(
                        FolioSharedKeys.bookCover(book.id),
                        placeHolderSize = SharedTransitionScope.PlaceHolderSize.contentSize,
                    )
                    .clickable { onCoverClick() },
            )

            Column(
                modifier = Modifier.weight(1f),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceHair)
            ) {
                Text(
                    text = book.title,
                    style = FolioTheme.typography.headlineMedium,
                    color = FolioTheme.colors.onSurface,
                    // Paired with the shelf's title: the run of text travels with
                    // its cover instead of the cover arriving and the words
                    // appearing separately.
                    modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.bookTitle(book.id))
                )

                book.subtitle?.takeIf { it.isNotBlank() }?.let {
                    Text(
                        text = it,
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }

                Text(
                    text = book.displayAuthor,
                    style = FolioTheme.typography.titleSmall,
                    color = accent,
                    modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.bookAuthor(book.id))
                )

                Spacer(Modifier.height(FolioTokens.space2))

                // Progress as a figure with the estimate as its caption; the seam
                // below carries the bar. §2.6 holds — one progress form here.
                FolioFigure(
                    value = book.progressPercent.toString(),
                    unit = "%",
                    caption = remainingTimeCaption(book, wordsRead, sessions),
                    accent = accent,
                    emphasis = FigureScale.Quiet,
                )
                Spacer(Modifier.height(FolioTokens.space1))
                FolioProgressBar(
                    progress = book.normalizedProgress.toFloat(),
                    color = accent,
                )
            }
        }

        BookMetadataGrid(book = book)

        Box(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
            BookChipsRow(
                series = series,
                collections = collections,
                tags = tags,
                onTagClick = onTagClick,
                onSeriesClick = onSeriesClick,
                onCollectionClick = onCollectionClick,
                onAddTags = onAddTags,
                onSuggestTags = onSuggestTags
            )
        }

        book.description?.takeIf { it.isNotBlank() }?.let {
            Box(modifier = Modifier.padding(horizontal = FolioTokens.gutter)) {
                BookDescription(description = it)
            }
        }
    }
}

@Composable
private fun BookMetadataGrid(book: Book) {
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .folioSunken(FolioShapes.edgeStart)
            .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space2),
    ) {
        MetadataRow("Publisher", book.publisher ?: "—")
        MetadataRow("Language", book.language ?: "—")
        MetadataRow("ISBN", book.isbn ?: "—")
        MetadataRow("Chapters", "${book.chapterCount}")
        MetadataRow("Words", formatCount(book.totalWords))
        book.publicationDate?.let {
            MetadataRow("Published", it.toString().take(10), last = true)
        } ?: MetadataRow("Published", "—", last = true)
    }
}

@Composable
private fun MetadataRow(label: String, value: String, last: Boolean = false) {
    Column {
        Row(
            modifier = Modifier.fillMaxWidth().padding(vertical = FolioTokens.space2),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)
        ) {
            Text(
                text = label,
                style = FolioTheme.typography.bodySmall,
                color = FolioTheme.colors.onSurfaceVariant,
                modifier = Modifier.width(96.dp)
            )
            Text(
                text = value,
                style = FolioTheme.typography.titleSmall,
                color = FolioTheme.colors.onSurface,
                modifier = Modifier.weight(1f),
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
        if (!last) FolioRule()
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun BookChipsRow(
    series: Series?,
    collections: List<Collection>,
    tags: List<Tag>,
    onTagClick: (Tag) -> Unit,
    onSeriesClick: (Series) -> Unit,
    onCollectionClick: (Collection) -> Unit,
    onAddTags: () -> Unit,
    onSuggestTags: (() -> Unit)? = null,
) {
    FlowRow(
        modifier = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp)
    ) {
        series?.let {
            AssistChip(
                onClick = { onSeriesClick(it) },
                label = { Text("Series: ${it.name}") },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = FolioTheme.colors.primaryContainer,
                    labelColor = FolioTheme.colors.onPrimaryContainer
                )
            )
        }

        collections.forEach { collection ->
            AssistChip(
                onClick = { onCollectionClick(collection) },
                label = { Text(collection.name) },
                colors = AssistChipDefaults.assistChipColors(
                    containerColor = FolioTheme.colors.tertiaryContainer,
                    labelColor = FolioTheme.colors.onTertiaryContainer
                )
            )
        }

        tags.forEach { tag ->
            SuggestionChip(
                onClick = { onTagClick(tag) },
                label = { Text(tag.name) },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = FolioTheme.colors.secondaryContainer,
                    labelColor = FolioTheme.colors.onSecondaryContainer
                )
            )
        }

        SuggestionChip(
            onClick = onAddTags,
            label = { Text("+ Tag") },
            colors = SuggestionChipDefaults.suggestionChipColors(
                containerColor = FolioTheme.colors.surface,
                labelColor = FolioTheme.colors.onSurfaceVariant
            )
        )

        // "Suggest" sits next to "+ Tag" because it is the same errand — the reader wants
        // this book categorised and is deciding whether to do it themselves or look at a
        // proposal first. It is only drawn when a tagger is actually wired, and its result
        // opens a panel rather than writing anything: a suggestion is not an assignment.
        onSuggestTags?.let { suggest ->
            SuggestionChip(
                onClick = suggest,
                label = { Text("Suggest") },
                colors = SuggestionChipDefaults.suggestionChipColors(
                    containerColor = FolioTheme.colors.surface,
                    labelColor = FolioTheme.colors.onSurfaceVariant
                )
            )
        }
    }
}

/**
 * The caption under the progress figure: how long is left, at the pace the reader
 * has actually been reading this book.
 *
 * This used to compute from a hardcoded `220 wpm`, which made the detail page the
 * one surface in the app that ignored its own measurements — the library rows, Home
 * and the reading card all project through [finishHorizon], which averages the last
 * seven days of real session history (`readingPaceWordsPerDay`). The two disagreed
 * about the same book, and a reader who tracks their pace sees that immediately.
 *
 * The policy now lives in [readingTimeCaption], beside the projection it defers to,
 * so the fallback rate and the measurement floor are testable without a UI host.
 */
private fun remainingTimeCaption(book: Book, wordsRead: Long, sessions: List<ReadingSession>): String =
    readingTimeCaption(
        totalWords = book.totalWords,
        progress = book.normalizedProgress,
        wordsRead = wordsRead,
        bookSessions = sessions,
    ) ?: "Finished"
