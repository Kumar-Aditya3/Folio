package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * The hue a *place* in the app wears.
 *
 * Chrome used to be byte-identical across screens — one `primary` at one alpha on
 * every chip, every section card, every slider — which is most of why four
 * different rooms read as one room. [folioDestinationAccent] already spends the
 * four §12.3 semantic accents on destinations for the nav capsule and the
 * masthead's segment switch; this is the same idea carried *inside* a screen, for
 * the controls that have no route of their own.
 *
 * A host wraps its content in [FolioSection] with the hue that place owns (the
 * Library host gives Books/Documents/Manga three different ones), and every
 * section-aware control below it — [com.folio.reader.ui.components.FolioChip],
 * [com.folio.reader.ui.components.FolioSectionCard], the sliders — picks it up
 * without being passed anything.
 *
 * Null means "no section wraps this tree", which is the normal case for previews,
 * tests and the reader's own chrome: [sectionAccent] then falls back to the
 * palette's `primary`, so an unwrapped screen keeps exactly the colour it had.
 */
val LocalFolioSectionAccent = compositionLocalOf<Color?> { null }

/**
 * Claims [accent] as the signature hue for everything composed inside [content].
 *
 * Deliberately a colour rather than a key: the caller already knows whether this
 * place is the theme's `primary`, its `tertiary` or a cover's own accent, and a
 * second id→colour table here would be a copy of [folioDestinationAccent]'s with
 * nothing to keep the two in step (Rule 3).
 */
@Composable
fun FolioSection(accent: Color, content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFolioSectionAccent provides accent, content = content)
}

/**
 * The current section's hue, or the palette's `primary` where no section is
 * claimed. Read it in a control's default argument so an explicit colour passed
 * by a call site still wins.
 */
val FolioTheme.sectionAccent: Color
    @Composable
    get() = LocalFolioSectionAccent.current ?: LocalFolioColors.current.primary
