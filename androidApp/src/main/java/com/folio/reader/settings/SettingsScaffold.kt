package com.folio.reader.settings

import com.folio.reader.ui.components.FolioGlassPanel
import com.folio.reader.ui.settings.FolioMaterialsPreview
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
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
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.settings.SettingsLivePreview
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.folioBarTopInset

/** Route values for `settings/{category}` (§3.5 FOLIO_IMPLEMENTATION_SPEC). */
object FolioSettingsCategory {
    const val THEMES = "themes"
    const val DISCOVER = "discover"

    /**
     * Animations — how much the app and the living room move. Its own destination rather than a
     * section inside Transparency: a reader looking for "this app is too busy" should not have to
     * know that the answer is behind the panel about how solid the glass is.
     */
    const val ANIMATIONS = "animations"
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
    const val NARRATION = "narration"
    const val CLOUD_SYNC = "cloud_sync"
    const val ADVANCED = "advanced"
    const val STATS = "stats"
    const val MANGA = "manga"
    const val READING_SURFACES = "reading_surfaces"

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
 * The controls sit on the shared diffuse glass panel so a category's settings read as a
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
    materialPreviewSettings: ReaderSettings? = null,
    /**
     * Optional plain-scrolling body, for a category whose content is one long surface rather than
     * a handful of controls. Supplying this keeps [content]'s look exactly — one [FolioGlassPanel]
     * of whatever height it needs — but scrolls it in a `Column` instead of a `LazyColumn`.
     *
     * Why: the default path puts the whole body in a single `item { FolioGlassPanel { content() } }`.
     * A lazy item is measured with unbounded height, and once that child is taller than the
     * viewport the rows past the fold are painted but stop answering touches. That is what made
     * every Discover extension from Manhwa18 down unselectable at any scroll offset while the rows
     * above it toggled. A `Column` inside `verticalScroll` places its children at full height and
     * hit-tests through the scroll offset normally, so every row stays live — and, unlike emitting
     * one lazy item per row, the surface still reads as one continuous panel.
     */
    scrollBody: (@Composable ColumnScope.() -> Unit)? = null,
    content: @Composable () -> Unit = {},
) {
    val headerState = rememberFolioHeaderState()
    val topInset = folioBarTopInset()

    Box(modifier = Modifier.fillMaxSize()) {
        if (scrollBody != null) {
            // Same paddings, same arrangement, same backdrop wiring as the LazyColumn path below —
            // only the scroller changes, so the surface looks identical and every row stays
            // hit-testable however tall the body gets.
            Column(
                modifier = Modifier
                    .fillMaxSize()
                    .nestedScroll(headerState.nestedScrollConnection)
                    .folioBackdropSource()
                    .verticalScroll(rememberScrollState())
                    .padding(
                        start = FolioTokens.gutter,
                        end = FolioTokens.gutter,
                        top = topInset + FolioTokens.space2,
                        bottom = FolioTokens.spaceMovement,
                    ),
                verticalArrangement = Arrangement.spacedBy(FolioTokens.spaceMovement),
            ) {
                if (materialPreviewSettings != null) FolioMaterialsPreview(materialPreviewSettings)
                if (livePreviewSettings != null) SettingsLivePreview(livePreviewSettings)
                scrollBody()
            }
        } else {
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
            if (materialPreviewSettings != null) {
                item { FolioMaterialsPreview(materialPreviewSettings) }
            }
            if (livePreviewSettings != null) {
                item { SettingsLivePreview(livePreviewSettings) }
            }
            // Controls read as a group on a resting panel rather than floating loose on the
            // wallpaper — the live preview above keeps its own surface and stays separate.
            item { FolioGlassPanel { content() } }
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
