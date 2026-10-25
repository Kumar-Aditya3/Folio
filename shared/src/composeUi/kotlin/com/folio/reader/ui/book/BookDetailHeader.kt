@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.book

import com.folio.reader.ui.components.*
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.foundation.background
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
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Collection
import com.folio.reader.model.ReadingSession
import com.folio.reader.model.Series
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.FigureScale
import com.folio.reader.ui.components.FolioCoverPlate
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioSharedKeys
import com.folio.reader.ui.components.sharedElementOrNoop
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.components.FolioFigure
import com.folio.reader.ui.components.FolioProgressBar
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.readingTimeCaption
import com.folio.reader.ui.components.folioRaised
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.components.folioThemeRim
import com.folio.reader.ui.components.rememberCoverAccent
import com.folio.reader.ui.components.rememberCoverLight
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.inFlowGlassActive
import com.folio.reader.ui.theme.FolioAmbientSource
import com.folio.reader.ui.theme.FolioCoverLightSource
import com.folio.reader.ui.theme.FolioShapeFamily
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.rememberFolioShape
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
    val colors = FolioTheme.colors
    val accent = rememberCoverAccent(book.coverPath, colors.accentProgress)
    FolioAmbientSource(accent)
    FolioCoverLightSource(rememberCoverLight(book.coverPath))
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceBeat)) {
        FolioArtworkScene(FolioArtwork("book:${book.id}", book.coverPath), Modifier.fillMaxWidth()) {
            Column(Modifier.fillMaxWidth().padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                BoxWithConstraints(Modifier.fillMaxWidth()) {
                    val coverWidth = (maxWidth * .36f).coerceAtMost(136.dp)
                    val stack = maxWidth < 310.dp || LocalDensity.current.fontScale > 1.3f
                    val cover: @Composable () -> Unit = {
                        FolioCoverPlate(book.coverPath, book.title, book.displayAuthor,
                            width = coverWidth, halo = accent, haloStrength = .08f,
                            elevation = FolioTokens.elevationRaised, suppressFallbackText = true,
                            modifier = Modifier.sharedElementOrNoop(FolioSharedKeys.bookCover(book.id),
                                placeholderSize = SharedTransitionScope.PlaceholderSize.ContentSize)
                                .clickable(onClick = onCoverClick),
                            overlay = { com.folio.reader.ui.components.FolioTraceSpine(book.id,
                                book.normalizedProgress.toFloat(), Modifier.align(Alignment.CenterEnd)) })
                    }
                    val identity: @Composable () -> Unit = {
                        Column(verticalArrangement = Arrangement.spacedBy(7.dp)) {
                            FolioEyebrow(if (book.normalizedProgress > 0.0) "Reading" else "In your library", accent = colors.primary)
                            Text(book.title, style = FolioTheme.typography.headlineMedium, color = colors.onSurface,
                                modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.bookTitle(book.id)))
                            book.subtitle?.takeIf { it.isNotBlank() }?.let {
                                Text(it, style = FolioTheme.typography.bodyMedium, color = colors.onSurfaceVariant)
                            }
                            Text(book.displayAuthor, style = FolioTheme.typography.titleSmall, color = colors.onSurfaceVariant,
                                modifier = Modifier.sharedTextOrNoop(FolioSharedKeys.bookAuthor(book.id)))
                        }
                    }
                    if (stack) Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                        cover(); identity()
                    } else Row(horizontalArrangement = Arrangement.spacedBy(FolioTokens.space4),
                        verticalAlignment = Alignment.CenterVertically) {
                        cover(); Box(Modifier.weight(1f)) { identity() }
                    }
                }
                val progress = book.normalizedProgress.toFloat().coerceIn(0f, 1f)
                com.folio.reader.ui.components.FolioReadingTrace(book.id, book.title, progress)
                val caption = remainingTimeCaption(book, wordsRead, sessions)
                FolioActionDock(if (progress <= 0f) "Start reading" else "Continue reading",
                    if (caption == "Finished" && progress < .999f) "${book.progressPercent}% complete" else caption,
                    onClick = onCoverClick, accent = colors.primary, progress = progress,
                    accessibilityLabel = "Read ${book.title}")
            }
        }
        BookMetadataGrid(book = book, accent = accent)
        Box(Modifier.padding(horizontal = FolioTokens.gutter)) {
            BookChipsRow(series, collections, tags, onTagClick, onSeriesClick, onCollectionClick,
                onAddTags, onSuggestTags)
        }
        book.description?.takeIf { it.isNotBlank() }?.let {
            Box(Modifier.padding(horizontal = FolioTokens.gutter).folioPaper(accent).padding(FolioTokens.space3)) {
                BookDescription(description = it)
            }
        }
    }
}

@Composable
private fun BookMetadataGrid(book: Book, accent: Color) {
    // Metadata rests on the same diffuse reference material used by settings.
    Column(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = FolioTokens.gutter)
            .folioReference()
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
