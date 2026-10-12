package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.ui.graphics.Color

/**
 * How many of the app's own surfaces a node is already drawn *inside*.
 *
 * The five materials in `FolioSurfaces` sell depth the way a real object does: a
 * cast shadow, a lit rim, an edge bevel. Every one of those cues is a statement
 * about the **page behind the surface** — and all of them become lies the moment
 * the thing behind the surface is another surface. A panel's 2dp shadow then lands
 * on its parent's fill instead of on the field; its 1dp hairline traces a second
 * rounded rectangle a few pixels inside the parent's own; a veil's two-stroke
 * bevel traces a third. Stack two or three tiers and the screen grows a visible
 * box-inside-a-box texture.
 *
 * That is exactly what Settings had. The category shell is one big [folioVeil]
 * card and nearly every control inside it brought its own rimmed, shadowed slab —
 * the failure already diagnosed once for the sync form ("a `folioPanel` here
 * stacked an opaque, hard-rimmed slab inside that glass") and then fixed only at
 * that one call site. Fixing it per call site is how it came back: there is no
 * place a call site can look to find out whether it is nested.
 *
 * So depth is carried rather than guessed. Containers that own a surface *and*
 * host arbitrary content claim their children with [FolioSurfaceContents]; the
 * materials read this and spend only the cues that are still true at that depth.
 * Zero — the default, and what every un-wrapped tree, test and preview sees —
 * leaves every material byte-for-byte the surface it has always been.
 *
 * A tree that paints its own ground is *not* nested, however deep it sits:
 * [FolioTheme.AppTheme] resets this to 0 (see [FolioSurfaceRoot]), so the
 * Appearance mock-ups keep showing the real materials at full strength, which is
 * the entire point of them.
 */
val LocalFolioSurfaceDepth = compositionLocalOf { 0 }

/**
 * The plane the surface a node sits *inside* is painted with, if that surface published one.
 *
 * P7: depth was still carried by *stepping tiers* — a panel inside a section card was painted from
 * `panelFill` while the card was painted from `veilFill`, so the division between them was two
 * different materials meeting, which the eye reads as a box inside a box. The nested surface should
 * instead be made of the same stuff as its parent and be separated by its **rim** alone. This
 * carries the parent's plane down so it can do exactly that.
 *
 * Null (the default, and what every un-wrapped tree sees) means "no parent published a plane", and
 * a material then paints its own tier exactly as it always did — so nothing changes for a surface
 * that is not inside another one.
 */
val LocalFolioSurfaceFill = compositionLocalOf<Color?> { null }

/**
 * Declares [content] as living one surface deeper than its caller.
 *
 * Applied by the container composables that wrap content in a material —
 * [com.folio.reader.ui.components.FolioSectionCard], the settings category shell, the
 * More hub's sections and the overlay host's pane.
 *
 * [fill] is the plane *this* container is painted with, so the surfaces it contains can be made of
 * the same stuff (see [LocalFolioSurfaceFill]). Null — the default — publishes nothing and leaves
 * the ambient value alone, so a container that does not know its own plane (or has none) is
 * unchanged.
 *
 * Deliberately not something a `Modifier` can do: a modifier is composed in its
 * *parent's* scope, so a surface can never provide anything to the tree it wraps.
 * Hanging it off the container composables is what keeps the surface's own
 * treatment at full strength while only what it *contains* is quieted.
 */
@Composable
fun FolioSurfaceContents(
    fill: Color? = null,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalFolioSurfaceDepth provides LocalFolioSurfaceDepth.current + 1,
        LocalFolioSurfaceFill provides (fill ?: LocalFolioSurfaceFill.current),
        content = content,
    )
}

/**
 * Declares [content] a room of its own: a tree that supplies its own ground, so
 * the surfaces inside it are resting on a page again however deeply the node sits.
 *
 * The Appearance preview is the case this exists for. It draws a [folioField] and
 * then the real bar, card, sheet and capsule over it, and those four must read
 * exactly as they will on the running screen — full shadow, full bevel — or the
 * transparency sliders above them are previewing something the app never renders.
 */
@Composable
fun FolioSurfaceRoot(content: @Composable () -> Unit) {
    CompositionLocalProvider(LocalFolioSurfaceDepth provides 0, content = content)
}

/**
 * True when the calling node is already inside one of the app's surfaces. Read it
 * in a material to decide which depth cues are still honest — never to decide
 * whether to draw the plane itself, which is what makes the group legible and is
 * true at any depth.
 */
val FolioTheme.embedded: Boolean
    @Composable
    get() = LocalFolioSurfaceDepth.current > 0

/**
 * Asks the glass materials under this node to wear their *calm* light.
 *
 * The reader's floating chrome and its in-reader settings/side panels are the one
 * place the app's glass is always on screen over the reading surface, so the full
 * specular treatment — a tight bright sheen band, a lit edge bevel, and (on dark
 * reading themes) a rim tinted toward the theme's secondary hue — reads as "shiny"
 * and "edge-coloured" against otherwise plain paper. When this is true a [folioVeil]
 * dims every light catch by [FOLIO_READER_CALM_SHEEN] and strips the hue from the
 * catch colour, so the islands keep their frosted body, rounded silhouette and a
 * faint neutral edge — modern, but no longer glossy or book-tinted.
 *
 * False — the default, and what every surface outside the reader sees — leaves the
 * material byte-for-byte what it has always been (Rule 19). The reader provides this
 * from the inverse of the editorial-styling preference, so the expressive look is
 * opt-in and the calm look is the default.
 */
val LocalFolioReaderCalmGlass = compositionLocalOf { false }

/**
 * How much of a glass surface's light catch survives under [LocalFolioReaderCalmGlass].
 * Scales the §17 sheen band, the in-flow top catch, the bevel's lit rim, the lens
 * specular and the film glints together — the fill, hairline, blur and shadow are
 * untouched, so the surface loses its gloss without losing its body or its shape.
 */
const val FOLIO_READER_CALM_SHEEN = 0.4f
