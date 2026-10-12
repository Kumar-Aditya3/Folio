package com.folio.reader.settings

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.nestedscroll.nestedScroll
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.folioVeilPlane
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.settings.SettingsLivePreview
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSurfaceContents
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.folioBarTopInset

/** Route values for `settings/{category}` (§3.5 FOLIO_IMPLEMENTATION_SPEC). */
object FolioSettingsCategory {
    const val THEMES = "themes"
    const val TRANSPARENCY = "transparency"
    const val CUSTOM_THEME = "custom_theme"
    const val DEFAULTS = "defaults"

    /**
     * Dedicated **document** settings — the typography, page and page theme a
     * PDF/Word/HTML document opens with. Separate from [DEFAULTS] because a
     * document is not a book: these are the document row's own defaults
     * (`ReaderSettings.documentSettings`), not the book defaults.
     */
    const val DOCUMENTS = "documents"

    /** §14.2: the merged Typography/Layout/Formatting destination. */
    const val TEXT_AND_PAGE = "text_and_page"

    // Pre-merge route values, kept resolving so deep links and bookmarks from
    // older builds land on the merged screen instead of a dead end.
    const val TYPOGRAPHY = "typography"
    const val LAYOUT = "layout"
    const val FORMATTING = "formatting"

    const val READING = "reading"
    const val CLOUD_SYNC = "cloud_sync"
    const val ADVANCED = "advanced"
    const val STATS = "stats"
    const val MANGA = "manga"

    /** Library scanning (folder / device-wide import): its own destination under "Library tools". */
    const val LIBRARY_SCAN = "library_scan"

    /** On-device semantic search: model download and library index (ML_PLAN Phase 4). */
    const val SEMANTIC_SEARCH = "semantic_search"

    /** Storage sense: on-disk usage by category, with a donut breakdown. */
    const val STORAGE = "storage"
}

/**
 * Shared shell for a pushed settings category screen: the masthead floats over
 * the panel, and the panel scrolls underneath it.
 *
 * The controls sit on a resting `folioPanel` so a category's settings read as a
 * grouped object rather than loose text floating on the wallpaper. An earlier
 * version dropped the surface entirely (the field showed straight through), which
 * left every control sitting on the direct background with nothing to belong to;
 * the panel gives the group an edge again. The live preview above keeps its own
 * surface because a preview genuinely *is* a separate object.
 *
 * The preview leads. Beneath the controls it was below the fold on a phone: the
 * Typography screen has seven of them, so every slider moved something the reader
 * could not see, and the whole category looked inert. Above them, one drag proves
 * what the screen is for.
 */
@Composable
fun SettingsCategoryScaffold(
    title: String,
    onBack: () -> Unit,
    livePreviewSettings: ReaderSettings? = null,
    content: @Composable () -> Unit
) {
    val headerState = rememberFolioHeaderState()
    val topInset = folioBarTopInset()

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(headerState.nestedScrollConnection)
                // §16: the scrolling panel is the backdrop the floating masthead
                // (and the capsule, on bar routes) blurs.
                .folioBackdropSource(),
            contentPadding = PaddingValues(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                // The bar's at-rest height, paid by the scroller alone so the first
                // control clears the glass while everything after it passes under.
                top = topInset + FolioTokens.space2,
                bottom = FolioTokens.spaceMovement,
            ),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceMovement)
        ) {
            if (livePreviewSettings != null) {
                item { SettingsLivePreview(livePreviewSettings) }
            }
            // Controls read as a group on a resting panel rather than floating loose on the
            // wallpaper — the live preview above keeps its own surface and stays separate.
            item {
                androidx.compose.foundation.layout.Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .folioVeil(
                            FolioShapes.card,
                            tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
                        )
                        .padding(horizontal = FolioTokens.space3, vertical = FolioTokens.space3),
                    verticalArrangement = Arrangement.spacedBy(FolioTokens.space3),
                ) {
                    // This shell *is* a surface, so every control a category puts inside it
                    // is nested by construction — which is why the category screens grew a
                    // box-inside-a-box texture: a dropdown's panel, a theme tile's slab and a
                    // colour editor's well each brought a full shadow and a full hairline to
                    // draw a second and third rounded rect inside this one. Claiming the
                    // content tells those materials where they are, so they keep their plane
                    // and drop the depth cues that were describing a page that is not there.
                    // One declaration here replaces the per-call-site fix that kept coming
                    // undone (see SettingsPanelSync's own note). FolioSurfaceContents.
                    //
                    // §19: the shell also publishes the exact plane it is painted with
                    // (folioVeilPlane mirrors folioVeil's own fill arithmetic), so a nested
                    // surface is a floor of the shell's own material rather than a second
                    // tier of fill — the division is the rim, not a plane change.
                    FolioSurfaceContents(folioVeilPlane()) { content() }
                }
            }
        }

        // Last, so it is on top: a bar with nothing behind it but the page's own
        // flat field has nothing to refract, and reads as no bar at all.
        FolioTopBar(
            title = title,
            collapse = headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            navigationIcon = {
                IconButton(onClick = onBack) {
                    Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back")
                }
            }
        )
    }
}
