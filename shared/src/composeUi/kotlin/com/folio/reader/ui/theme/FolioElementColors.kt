package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.lerp
import androidx.compose.ui.graphics.compositeOver
import com.folio.reader.ui.components.legibleOn

/** Element roles stay stable when the route, section accent, or featured cover changes. */
@Immutable
data class FolioElementColors(
    val paper: Color,
    val reference: Color,
    val inset: Color,
    val chrome: Color,
    val action: Color,
    val onAction: Color,
    val actionInk: Color,
    val selection: Color,
    val onSelection: Color,
    val navigationSelection: Color,
    val onNavigationSelection: Color,
    val feature: Color,
    val ink: Color,
    val mutedInk: Color,
    val border: Color,
)

internal fun elementColorsFor(colors: FolioColors): FolioElementColors {
    val dark = luminanceOf(colors.background) < .5f
    val reference = colors.referenceSurface
    val selection = lerp(reference, colors.primary, if (dark) .20f else .12f)
    val inset = lerp(reference, colors.background, if (dark) .36f else .28f)
    val surfaces = listOf(colors.paperSurface, reference, inset, colors.chromeSurface, selection)
    // Pair existing ink roles to every material they are used on. This also supports
    // custom palettes without assuming their authored planes share one luminance.
    fun inkForAll(requested: Color): Color = surfaces.fold(requested) { ink, fill ->
        legibleOn(ink, fill, colors.onSurface)
    }
    val ink = inkForAll(colors.onSurface)
    // Preserve achromatic signatures rather than inventing hue through fillForm.
    val navigation = (if (oklabChroma(colors.primary) < .008f) colors.primary
        else fillForm(colors.primary)).copy(alpha = .76f)
    return FolioElementColors(
        paper = colors.paperSurface,
        reference = reference,
        inset = inset,
        chrome = colors.chromeSurface,
        action = colors.primary,
        onAction = legibleOn(colors.onPrimary, colors.primary, ink),
        actionInk = legibleOn(colors.primary, reference, ink),
        selection = selection,
        onSelection = legibleOn(colors.primary, selection, ink),
        navigationSelection = navigation,
        onNavigationSelection = legibleOn(colors.onPrimary,
            navigation.compositeOver(colors.chromeSurface), ink),
        feature = lerp(colors.paperSurface, colors.primary, if (dark) .13f else .07f),
        ink = ink,
        mutedInk = inkForAll(colors.onSurfaceVariant),
        border = lerp(reference, ink, if (dark) .22f else .18f),
    )
}

val FolioTheme.elements: FolioElementColors
    @Composable get() {
        val colors = colors
        return remember(colors) { elementColorsFor(colors) }
    }
