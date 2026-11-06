package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/** Section identity for headings and small cues; material and action roles stay theme-wide. */
val LocalFolioSectionAccent = compositionLocalOf<Color?> { null }

/**
 * Provides [accent] for headings and identity cues inside [content].
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
 * claimed. Functional controls use FolioTheme.elements instead.
 */
val FolioTheme.sectionAccent: Color
    @Composable
    get() = LocalFolioSectionAccent.current ?: LocalFolioColors.current.primary
