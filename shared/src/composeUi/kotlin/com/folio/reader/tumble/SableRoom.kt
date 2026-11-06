package com.folio.reader.tumble

import androidx.compose.foundation.*
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowForward
import androidx.compose.material.icons.automirrored.outlined.MenuBook
import androidx.compose.material.icons.outlined.BookmarkBorder
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.*
import androidx.compose.ui.draw.clip
import androidx.compose.ui.geometry.*
import androidx.compose.ui.graphics.*
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.*
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.*
import com.folio.reader.ui.theme.*

internal fun ArcadeRun.keepsakeEligible() = state.finished && !abandoned && !state.practice && state.score > 0

/** The book and its bookmark share one paper surface, from Home to the games. */
@Composable internal fun SableDeskScene(data: TumbleLibrary, pica: PicaSession,
    onPet: () -> Unit, onStretch: () -> Unit, onGame: (PocketGame) -> Unit, onBookmark: () -> Unit,
    onJournal: () -> Unit = onBookmark) {
    val colors = FolioTheme.colors
    val canContinue = pica.bookId.isNotBlank() && pica.onContinue != null
    Column(Modifier.widthIn(max = 640.dp).fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(20.dp)) {
        FolioPaperSheet {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.spacedBy(14.dp)) {
                Box(Modifier.width(132.dp).height(158.dp)) {
                    if (pica.bookTitle.isNotBlank()) Box(Modifier.align(Alignment.BottomEnd)
                        .clickable(enabled = canContinue, role = Role.Button) { pica.continueReading() }
                        .semantics(mergeDescendants = true) {
                            contentDescription = if (canContinue) "Continue reading ${pica.bookTitle}" else "Your last book: ${pica.bookTitle}"
                        }) {
                        FolioCoverPlate(pica.bookCover, pica.bookTitle, pica.bookAuthor,
                            modifier = Modifier.testTag("sable-book-cover"), width = FolioTokens.coverShelf,
                            elevation = FolioTokens.elevationRaised, small = true)
                    }
                    SableAvatar(Modifier.align(Alignment.TopStart).size(width = 68.dp, height = 94.dp)
                        .testTag("book-attached-bookmark"),pica.personality(data).pose,pica.reaction,pica.cue,
                        onBookmark,onStretch,onPet,data.decoration,tapLabel="Open kept passage")
                }
                Column(Modifier.weight(1f), verticalArrangement = Arrangement.spacedBy(7.dp)) {
                    FolioEyebrow(if (pica.bookTitle.isBlank()) "YOUR READING SPACE" else "READING NOW")
                    Text(pica.bookTitle.ifBlank {"Your next chapter"}, style = FolioTheme.typography.titleMedium,
                        color = colors.textPrimary, maxLines = 3, overflow = TextOverflow.Ellipsis)
                    if (pica.bookAuthor.isNotBlank()) Text(pica.bookAuthor,style = FolioTheme.typography.bodySmall,
                        color = colors.textSecondary,maxLines = 2,overflow = TextOverflow.Ellipsis)
                    if (canContinue) TextButton({pica.continueReading()},contentPadding = PaddingValues(0.dp)) {
                        Text("Continue",style = FolioTheme.typography.labelMedium)
                    } else Text("Read a little. Play a little.",style = FolioTheme.typography.bodySmall,color = colors.textSecondary)
                }
            }
            if (pica.sessionLine.isNotBlank()) Text(pica.sessionLine,style = FolioTheme.typography.bodySmall,color = colors.textSecondary)
        }
        Column(verticalArrangement = Arrangement.spacedBy(12.dp)) {
            FolioEyebrow("TAKE A BREAK")
            Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(6.dp)) {
                PocketGame.entries.forEach { game ->
                    Column(Modifier.weight(1f).clip(RoundedCornerShape(18.dp))
                        .clickable(role = Role.Button) { onGame(game) }
                        .semantics(mergeDescendants = true) { contentDescription = "Open ${game.title}" }
                        .padding(horizontal = 4.dp,vertical = 8.dp),horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        PocketGameIcon(game,Modifier.size(64.dp))
                        Text(game.title,Modifier.fillMaxWidth().heightIn(min = 32.dp),style = FolioTheme.typography.labelLarge,
                            color = colors.textPrimary,textAlign = TextAlign.Center,maxLines = 2,overflow = TextOverflow.Ellipsis)
                        val best = data.arcadeRuns.filter {it.state.game == game && it.state.daily.isEmpty() && it.keepsakeEligible()}.maxOfOrNull {it.state.score}
                        Text(if(pica.active?.game == game) "Resume" else best?.let {"Best ${scoreText(it)}"} ?: when(game) {
                            PocketGame.SHELVES -> "Merge books"
                            PocketGame.LANTERNS -> "Find loops"
                            PocketGame.BALANCE -> "Build a stack"
                        },style = FolioTheme.typography.labelSmall,color = colors.textSecondary,maxLines = 1,overflow = TextOverflow.Ellipsis)
                    }
                }
            }
        }
        Row(Modifier.fillMaxWidth(),horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            TextButton(onBookmark,Modifier.weight(1f).heightIn(min = 48.dp).semantics {contentDescription = "Your bookmark. Open reading memories"}) {
                Icon(Icons.Outlined.BookmarkBorder,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Kept passage",style = FolioTheme.typography.labelMedium)
            }
            TextButton(onJournal,Modifier.weight(1f).heightIn(min = 48.dp).semantics {contentDescription = "Open reading journal"}) {
                Icon(Icons.AutoMirrored.Outlined.MenuBook,null,Modifier.size(18.dp))
                Spacer(Modifier.width(8.dp)); Text("Journal",style = FolioTheme.typography.labelMedium)
            }
        }
    }
}

/** Native game artwork mirrors the numbered tiles, connected lights and book tower on the boards. */
@Composable internal fun PocketGameIcon(game: PocketGame, modifier: Modifier = Modifier) {
    val colors = FolioTheme.colors
    val accent = when (game) {
        PocketGame.SHELVES -> colors.accentProgress
        PocketGame.LANTERNS -> colors.accentDiscovery
        PocketGame.BALANCE -> colors.accentAnnotation
    }
    val fill = lerp(FolioTheme.elements.paper, accent, .14f)
    val ink = legibleOn(accent, fill, FolioTheme.elements.ink)
    val shape = RoundedCornerShape(18.dp)
    Box(modifier.aspectRatio(1f).testTag("sable-game-icon-${game.name.lowercase()}")
        .clip(shape).background(fill).border(.8.dp, accent.copy(alpha = .25f), shape).padding(9.dp)) {
        if (game == PocketGame.SHELVES) BoxWithConstraints(Modifier.matchParentSize()) {
            val cell = maxWidth * .44f
            listOf(2, 4, 4, 8).forEachIndexed { index, value ->
                Box(Modifier.offset(maxWidth * .56f * (index % 2), maxHeight * .56f * (index / 2)).size(cell)
                    .background(accent.copy(alpha = .12f + index * .05f), RoundedCornerShape(4.dp))
                    .border(.7.dp, ink.copy(alpha = .5f), RoundedCornerShape(4.dp)), contentAlignment = Alignment.Center) {
                    Canvas(Modifier.matchParentSize()) {
                        drawLine(ink.copy(alpha = .35f), Offset(size.width * .17f, size.height * .15f),
                            Offset(size.width * .17f, size.height * .85f), .7.dp.toPx())
                    }
                    Text("$value", Modifier.padding(start = 3.dp), style = FolioTheme.typography.labelMedium, color = ink)
                }
            }
        } else Canvas(Modifier.matchParentSize()) {
            val unit = size.minDimension
            when (game) {
                PocketGame.LANTERNS -> {
                    repeat(9) { i ->
                        drawCircle(ink.copy(alpha = .12f), unit * .075f,
                            Offset(unit * (.16f + i % 3 * .34f), unit * (.16f + i / 3 * .34f)))
                    }
                    val points = listOf(Offset(.16f, .16f), Offset(.5f, .16f), Offset(.5f, .5f), Offset(.16f, .5f))
                    drawPath(Path().apply {
                        moveTo(points.first().x * unit, points.first().y * unit)
                        points.drop(1).forEach { lineTo(it.x * unit, it.y * unit) }; close()
                    }, ink.copy(alpha = .65f), style = Stroke(unit * .055f, cap = StrokeCap.Round, join = StrokeJoin.Round))
                    points.forEach { point ->
                        drawCircle(fill, unit * .11f, point * unit)
                        drawCircle(ink, unit * .065f, point * unit)
                        drawCircle(ink.copy(alpha = .35f), unit * .11f, point * unit, style = Stroke(.8.dp.toPx()))
                    }
                    drawCircle(colors.accentAnnotation, unit * .075f, Offset(unit * .84f, unit * .84f))
                }
                PocketGame.BALANCE -> {
                    repeat(3) { i ->
                        val x = unit * if (i == 1) .22f else .1f
                        val y = unit * (.72f - i * .27f)
                        val width = unit * (.74f - i * .06f)
                        drawRoundRect(accent.copy(alpha = .2f + i * .08f), Offset(x, y), Size(width, unit * .19f), CornerRadius(unit * .04f))
                        drawRoundRect(ink.copy(alpha = .8f), Offset(x, y), Size(width, unit * .19f), CornerRadius(unit * .04f), style = Stroke(1.dp.toPx()))
                        drawLine(colors.paperSurface, Offset(x + unit * .12f, y + unit * .095f),
                            Offset(x + width - unit * .04f, y + unit * .095f), unit * .07f, StrokeCap.Round)
                        drawLine(ink.copy(alpha = .6f), Offset(x + unit * .08f, y + unit * .035f),
                            Offset(x + unit * .08f, y + unit * .155f), .8.dp.toPx())
                    }
                    drawLine(ink.copy(alpha = .3f), Offset(unit * .06f, unit * .98f), Offset(unit * .94f, unit * .98f), 1.dp.toPx(), StrokeCap.Round)
                }
                else -> Unit
            }
        }
    }
}

@Composable internal fun SableNook(data: TumbleLibrary, pica: PicaSession, onPet: () -> Unit, onStretch: () -> Unit,
    onChoice: (Int) -> Unit, onGame: (PocketGame) -> Unit, onJournal: () -> Unit) {
    var discovery by rememberSaveable {mutableStateOf(false)}
    var bookmark by rememberSaveable {mutableStateOf(false)}
    val colors=FolioTheme.colors
    Column(Modifier.widthIn(max=640.dp).fillMaxSize().verticalScroll(rememberScrollState()),verticalArrangement=Arrangement.spacedBy(16.dp)) {
        SableDeskScene(data,pica,onPet=onPet,onStretch=onStretch,onGame=onGame,
            onBookmark={pica.react("bookmark");bookmark=!bookmark},onJournal=onJournal)
        if(bookmark) FolioPaperSheet {
            FolioEyebrow("KEPT PASSAGE")
            Text(pica.bookTitle.ifBlank {"Your next story belongs here"},style=FolioTheme.typography.titleMedium,color=colors.textPrimary)
            Text(if(pica.quote.isNotBlank()) "“${pica.quote}”" else "Highlight a passage in your book to find it here.",
                style=FolioTheme.typography.bodyMedium,color=colors.textSecondary,maxLines=6,overflow=TextOverflow.Ellipsis)
        }
        if(data.pendingDiscoveries()>0) {
            if(!discovery) FolioPaperSheet(accent=colors.accentDiscovery) {
                FolioEyebrow("READING PROMPT")
                Text("A question for the margin.",
                    style=FolioTheme.typography.bodyMedium,color=colors.textPrimary)
                TextButton({discovery=true}) {Text("Open prompt")}
            } else {
                val story=picaDiscoveries[data.bond.discoveries%picaDiscoveries.size]
                FolioPaperSheet(accent=colors.accentDiscovery) {
                    Text(story.question,style=FolioTheme.typography.bodyLarge,color=colors.textPrimary)
                    FolioActionButton({onChoice(0);discovery=false},Modifier.fillMaxWidth()) {Text(story.first)}
                    TextButton({onChoice(1);discovery=false},Modifier.fillMaxWidth()) {Text(story.second)}
                }
            }
        }
        if(pica.active!=null) FolioActionButton({onGame(pica.active!!.game)},Modifier.fillMaxWidth()) {Text("Resume ${pica.active!!.game.title}")}
        Spacer(Modifier.height(12.dp))
    }
}
