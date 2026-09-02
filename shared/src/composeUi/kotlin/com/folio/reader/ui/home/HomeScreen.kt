package com.folio.reader.ui.home

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.MenuBook
import androidx.compose.material3.Button
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.ui.components.BookCover
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.LoadingPlaceholder
import com.folio.reader.ui.components.ProgressRing
import com.folio.reader.ui.statistics.ReadingInProgress
import com.folio.reader.ui.statistics.WeekBars
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

/**
 * §5.4 Home surface: four FolioSectionCards in a fixed order — daily goal,
 * continue reading, because-you-finished, this week. The blank state is a
 * single sentence plus one import button and nothing else.
 */
@Composable
fun HomeScreen(
    state: HomeUiState,
    onOpenBook: (String) -> Unit,
    onOpenBookDetail: (String) -> Unit,
    onImportClick: () -> Unit
) {
    when {
        !state.loaded -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            LoadingPlaceholder()
        }
        !state.hasBooks -> Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
            EmptyState(
                icon = Icons.Filled.MenuBook,
                headline = "Your library is empty — import an EPUB to start.",
                action = { Button(onClick = onImportClick) { Text("Import a book") } }
            )
        }
        else -> LazyColumn(
            modifier = Modifier.fillMaxSize(),
            contentPadding = PaddingValues(FolioTokens.space4),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space4)
        ) {
            item { DailyGoalCard(state.todayMinutes, state.goalMinutes, state.streakDays) }
            if (state.continueReading.isNotEmpty()) {
                item { ContinueReadingCard(state.continueReading, onOpenBook) }
            }
            val finishedTitle = state.becauseFinishedTitle
            if (finishedTitle != null && state.candidates.size >= 2) {
                item { BecauseYouFinishedCard(finishedTitle, state.candidates, onOpenBookDetail) }
            }
            item { ThisWeekCard(state) }
        }
    }
}

@Composable
private fun DailyGoalCard(todayMinutes: Long, goalMinutes: Int, streakDays: Int) {
    FolioSectionCard(title = "Daily goal") {
        Box(modifier = Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
            ProgressRing(
                progress = if (goalMinutes > 0) {
                    (todayMinutes.toFloat() / goalMinutes).coerceIn(0f, 1f)
                } else 0f,
                modifier = Modifier.size(FolioTokens.ringLarge),
                strokeWidth = 6f
            )
            Text(
                text = "$todayMinutes / $goalMinutes min",
                style = FolioTheme.typography.labelMedium,
                color = FolioTheme.colors.onSurface,
                maxLines = 1
            )
        }
        Text(
            text = if (streakDays > 0) "$streakDays-day streak" else "No streak yet",
            style = FolioTheme.typography.bodyMedium,
            color = FolioTheme.colors.onSurfaceVariant,
            textAlign = TextAlign.Center,
            modifier = Modifier.fillMaxWidth().padding(top = FolioTokens.space2)
        )
    }
}

@Composable
private fun ContinueReadingCard(books: List<ReadingInProgress>, onOpenBook: (String) -> Unit) {
    FolioSectionCard(title = "Continue reading") {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            books.forEach { book ->
                Row(
                    modifier = Modifier.fillMaxWidth().clickable { onOpenBook(book.id) },
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Box(
                        modifier = Modifier
                            .width(48.dp)
                            .height(72.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        BookCover(coverPath = book.coverPath, title = book.title, author = book.author, small = true)
                    }
                    Spacer(Modifier.width(12.dp))
                    Column(
                        modifier = Modifier.weight(1f),
                        verticalArrangement = Arrangement.spacedBy(2.dp)
                    ) {
                        Text(
                            book.title,
                            style = FolioTheme.typography.titleMedium,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                        if (book.finishEstimate != null) {
                            Text(
                                book.finishEstimate,
                                style = FolioTheme.typography.bodySmall,
                                color = FolioTheme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                    Spacer(Modifier.width(FolioTokens.space2))
                    ProgressRing(progress = book.progress, modifier = Modifier.size(FolioTokens.ringSmall))
                }
            }
        }
    }
}

@Composable
private fun BecauseYouFinishedCard(
    finishedTitle: String,
    candidates: List<Book>,
    onOpenBookDetail: (String) -> Unit
) {
    FolioSectionCard(title = "Because you finished $finishedTitle") {
        Row(
            modifier = Modifier.fillMaxWidth(),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space2)
        ) {
            candidates.forEach { book ->
                Column(
                    modifier = Modifier
                        .weight(1f)
                        .clickable { onOpenBookDetail(book.id) },
                    horizontalAlignment = Alignment.CenterHorizontally
                ) {
                    Box(
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(84.dp)
                            .clip(RoundedCornerShape(4.dp))
                    ) {
                        BookCover(
                            coverPath = book.coverPath,
                            title = book.title,
                            author = book.displayAuthor,
                            small = true
                        )
                    }
                    Spacer(Modifier.height(FolioTokens.space1))
                    Text(
                        book.title,
                        style = FolioTheme.typography.labelSmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 2,
                        overflow = TextOverflow.Ellipsis,
                        textAlign = TextAlign.Center
                    )
                }
            }
        }
    }
}

@Composable
private fun ThisWeekCard(state: HomeUiState) {
    FolioSectionCard(title = "This week") {
        WeekBars(state.week)
        Spacer(Modifier.height(FolioTokens.space2))
        Text(
            "${state.startedThisWeek} started · ${state.finishedThisWeek} finished",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant
        )
    }
}
