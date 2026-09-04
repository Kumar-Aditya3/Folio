package com.folio.reader.ui.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.folio.reader.settings.LayoutMode
import com.folio.reader.settings.ReaderSettings
import com.folio.reader.settings.normalized
import com.folio.reader.ui.components.DropdownMenuButton
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.manga.MangaReaderMode
import com.folio.reader.ui.theme.FolioTheme

/**
 * The starting point every book and manga is given — and the one screen that
 * states the inheritance rule out loud.
 *
 * Folio's reading settings are *per item*: the first time a book opens it takes a
 * copy of the current defaults and owns it from then on (see
 * `ReaderSettings.toBookSettings`), so editing a default later deliberately
 * leaves whatever you are already reading alone. That is the right behaviour and
 * the wrong secret — unstated, it made Typography and Layout look broken: you
 * move a slider, go back to your book, nothing has changed.
 *
 * So this panel carries three things:
 *  - the two defaults that live nowhere else (prose page mode, manga direction);
 *  - the rule in plain words, beside the list of what is actually inherited;
 *  - the escape hatch — hand the current defaults to items already opened —
 *    without which the rule is a trap rather than a choice.
 */
@Composable
fun ReaderDefaultsPanel(
    settings: ReaderSettings,
    mangaDefaultMode: MangaReaderMode,
    onSettingsChange: (ReaderSettings) -> Unit,
    onMangaDefaultModeChange: (MangaReaderMode) -> Unit,
    /** Books that currently keep their own copy of an inherited field. */
    bookOverrideCount: Int = 0,
    /** Drops those copies so every book follows the defaults again. */
    onApplyToOpenedBooks: (() -> Unit)? = null,
    /** Manga that already have a reading mode of their own saved. */
    mangaOverrideCount: Int = 0,
    /** Drops those, so every manga opens in the default mode. */
    onApplyToOpenedManga: (() -> Unit)? = null,
) {
    val colors = FolioTheme.colors
    Column(
        modifier = Modifier.fillMaxWidth(),
        verticalArrangement = Arrangement.spacedBy(18.dp),
    ) {
        Text(
            "These are the values an item starts with. The first time you open a book or a " +
                "manga it takes its own copy, so changing a default afterwards never disturbs " +
                "something you are in the middle of — the reader's own controls do that.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )

        FolioEyebrow("Books", accent = colors.accentProgress)
        val pageModes = proseModes(settings.layoutMode)
        DropdownMenuButton(
            label = "Page mode",
            selected = settings.layoutMode.proseLabel(),
            options = pageModes.map { it.proseLabel() },
            onChange = { label ->
                val mode = pageModes.first { it.proseLabel() == label }
                onSettingsChange(settings.copy(layoutMode = mode))
            },
        )
        Text(
            "Scrolling runs a chapter as one column. Paged turns a screen at a time.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        Text(
            "A new book also inherits Typography (typeface, size, weight, line, letter, word " +
                "and paragraph spacing), Layout (text width, margins), Themes (its page " +
                "colours) and Reading (chapter title, progress, clock).",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        InheritanceEscapeHatch(
            count = bookOverrideCount,
            noun = "book",
            confirmBody = "Their own typeface, size, spacing, margins, text width, page theme " +
                "and reader display choices are dropped, and they follow these defaults from " +
                "now on. Reading positions, highlights and notes are untouched.",
            onApply = onApplyToOpenedBooks,
        )

        FolioEyebrow("Manga", accent = colors.accentDiscovery)
        DropdownMenuButton(
            label = "Reading mode",
            selected = mangaDefaultMode.readableLabel(),
            options = MangaReaderMode.entries.map { it.readableLabel() },
            onChange = { label ->
                onMangaDefaultModeChange(
                    MangaReaderMode.entries.first { it.readableLabel() == label }
                )
            },
        )
        Text(
            "Webtoon runs the pages end to end with no seams — right for long-strip releases. " +
                "The paged modes move one page at a time; right to left is how print manga is " +
                "read.",
            style = FolioTheme.typography.bodySmall,
            color = colors.onSurfaceVariant,
        )
        InheritanceEscapeHatch(
            count = mangaOverrideCount,
            noun = "manga",
            plural = "manga",
            confirmBody = "Their saved reading mode is dropped, so each one opens in the " +
                "default mode above. Reading progress and downloads are untouched.",
            onApply = onApplyToOpenedManga,
        )
    }
}

/**
 * Turns the inheritance rule from a trap into a choice. Hidden at zero — there
 * is nothing to say — and always behind a confirmation, because it discards
 * per-item work the reader may have done on purpose.
 */
@Composable
internal fun InheritanceEscapeHatch(
    count: Int,
    noun: String,
    plural: String = noun + "s",
    confirmBody: String,
    onApply: (() -> Unit)?,
) {
    if (count <= 0 || onApply == null) return
    var confirming by remember { mutableStateOf(false) }
    val subject = if (count == 1) "1 $noun" else "$count $plural"
    Column(verticalArrangement = Arrangement.spacedBy(2.dp)) {
        Text(
            "$subject ${if (count == 1) "keeps" else "keep"} settings of its own from an " +
                "earlier open.",
            style = FolioTheme.typography.bodySmall,
            color = FolioTheme.colors.onSurfaceVariant,
        )
        TextButton(
            onClick = { confirming = true },
            contentPadding = PaddingValues(vertical = 4.dp),
        ) {
            Text("Apply these defaults to $subject")
        }
    }
    if (confirming) {
        AlertDialog(
            onDismissRequest = { confirming = false },
            title = { Text("Apply defaults to $subject?") },
            text = {
                Text(
                    confirmBody,
                    style = FolioTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            },
            confirmButton = {
                TextButton(onClick = {
                    confirming = false
                    onApply()
                }) { Text("Apply") }
            },
            dismissButton = {
                TextButton(onClick = { confirming = false }) { Text("Cancel") }
            },
        )
    }
}

/**
 * Prose page modes, named for what they do rather than for the enum constant —
 * and only the ones still in service: `LayoutMode` keeps TWO_COLUMN and FOCUS so
 * old persisted settings still decode, and offering them here would have listed
 * two dead choices beside their own replacements. A [LayoutMode.SPREAD] already
 * chosen on desktop stays offered so this screen cannot silently reset it.
 */
private fun proseModes(current: LayoutMode): List<LayoutMode> =
    (listOf(LayoutMode.CONTINUOUS, LayoutMode.PAGINATED) + current.normalized).distinct()

private fun LayoutMode.proseLabel(): String = when (this.normalized) {
    LayoutMode.PAGINATED -> "Paged"
    LayoutMode.SPREAD -> "Two-page spread"
    else -> "Scrolling"
}

/**
 * Manga modes spelled out, in the same words the in-reader mode picker uses.
 * `settingsLabel()` renders `PAGED_RTL` as "Paged_rtl", which tells a reader
 * nothing about which way the pages go.
 */
fun MangaReaderMode.readableLabel(): String = when (this) {
    MangaReaderMode.WEBTOON -> "Webtoon (continuous vertical)"
    MangaReaderMode.PAGED_LTR -> "Paged (left to right)"
    MangaReaderMode.PAGED_RTL -> "Paged (right to left)"
    MangaReaderMode.PAGED_VERTICAL -> "Paged (vertical)"
}
