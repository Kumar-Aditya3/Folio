package com.folio.reader.ui.theme

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.ReadOnlyComposable
import androidx.compose.runtime.staticCompositionLocalOf
import com.folio.reader.settings.ReaderSettings

/**
 * User control over how solid the app's glass is.
 *
 * This deliberately lives *outside* [FolioAtmosphere]. The atmosphere is a pure
 * function of a palette and is asserted directly by the §15 design tests ("glass
 * not lid": `barGlass` alpha stays in a narrow band, `veilFill` stays effectively
 * opaque). If a user preference were folded into it those invariants would
 * describe a preference rather than the design, and the tests would have to be
 * loosened until they stopped meaning anything.
 *
 * So the atmosphere keeps stating what the design intends, and this multiplier is
 * applied at each paint site — the one place that knows *which* surface it is.
 *
 * Every value is a factor on the designed alpha: `1f` is exactly the shipped look,
 * `0f` is fully see-through. Nothing here can make a surface *more* opaque than
 * designed, which keeps the ceiling that the tests guard.
 */
@Immutable
data class FolioSurfaceOpacity(
    /** App top bars and the search masthead. */
    val topBar: Float = 1f,
    /** The floating nav capsule. */
    val navBar: Float = 1f,
    /** Cards, sheets and menus drawn with `Modifier.folioVeil`. */
    val panel: Float = 1f,
    /** Reader bars, side rail, TOC/notes drawers and the reader settings sheet. */
    val readerChrome: Float = 1f
) {
    companion object {
        val Default = FolioSurfaceOpacity()

        /** Below this a surface is invisible enough that its text is unreadable. */
        const val MIN = 0.25f
    }
}

/** Clamps a persisted value into the usable range; tolerates absent/old data. */
private fun sane(value: Float): Float =
    value.coerceIn(FolioSurfaceOpacity.MIN, 1f)

/** Reads the four opacity preferences off the settings blob. */
fun ReaderSettings.surfaceOpacity(): FolioSurfaceOpacity = FolioSurfaceOpacity(
    topBar = sane(topBarOpacity),
    navBar = sane(navBarOpacity),
    panel = sane(panelOpacity),
    readerChrome = sane(readerChromeOpacity)
)

val LocalFolioSurfaceOpacity = staticCompositionLocalOf { FolioSurfaceOpacity.Default }

/** The active opacity preferences. */
val FolioTheme.surfaceOpacity: FolioSurfaceOpacity
    @Composable
    @ReadOnlyComposable
    get() = LocalFolioSurfaceOpacity.current

/**
 * Fill alpha for a reader glass surface. Reader controls sit over a page the user
 * is reading, so they start effectively opaque ([FolioAtmosphere.veilFill]) and
 * the preference scales down from there.
 */
val FolioTheme.readerVeilAlpha: Float
    @Composable
    get() = atmosphere.veilFill.alpha * LocalFolioSurfaceOpacity.current.readerChrome
