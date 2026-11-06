package com.folio.reader.ui.statistics

import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyListScope
import androidx.compose.foundation.lazy.LazyListState
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.LocalFolioTopInset

/** Loading and loaded Stats share the page, section spacing and responsive geometry. */
@Composable
internal fun StatsPage(
    modifier: Modifier = Modifier,
    state: LazyListState = rememberLazyListState(),
    content: LazyListScope.() -> Unit,
) {
    LazyColumn(
        state = state,
        modifier = modifier.fillMaxSize().folioBackdropSource().folioPageWash(),
        contentPadding = PaddingValues(
            top = LocalFolioTopInset.current,
            bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
        ),
        content = content,
    )
}

@Composable
internal fun StatsOvertureLayout(
    figure: @Composable () -> Unit,
    rhythm: @Composable (Modifier) -> Unit,
    goal: @Composable () -> Unit,
) {
    BoxWithConstraints(Modifier.fillMaxWidth()
        .padding(horizontal = FolioTokens.gutter, vertical = FolioTokens.space3)) {
        val stacked = maxWidth < 330.dp || LocalDensity.current.fontScale > 1.2f
        if (stacked) Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            figure()
            Row(verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
                rhythm(Modifier.weight(1f))
                goal()
            }
        } else Row(verticalAlignment = Alignment.Top,
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                figure()
                rhythm(Modifier)
            }
            goal()
        }
    }
}

@Composable
internal fun StatsHeadlineLayout(
    streak: @Composable (Modifier) -> Unit,
    activeDays: @Composable (Modifier) -> Unit,
    year: @Composable (Modifier) -> Unit,
    finished: @Composable (Modifier) -> Unit,
) {
    Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            streak(Modifier.weight(1f))
            activeDays(Modifier.weight(1f))
        }
        FolioRule()
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(FolioTokens.space3)) {
            year(Modifier.weight(1f))
            finished(Modifier.weight(1f))
        }
    }
}

@Composable
internal fun StatsWeekLayout(
    heading: @Composable () -> Unit,
    plot: @Composable () -> Unit,
    readout: @Composable () -> Unit,
    hint: @Composable () -> Unit,
) {
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(12.dp)) {
        heading()
        plot()
        readout()
        hint()
    }
}
