package com.folio.reader.settings

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.ui.draw.clip
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.KeyboardArrowRight
import androidx.compose.material.icons.automirrored.filled.Label
import androidx.compose.material.icons.automirrored.filled.ManageSearch
import androidx.compose.material.icons.automirrored.filled.MenuBook
import androidx.compose.material.icons.filled.Animation
import androidx.compose.material.icons.filled.Backup
import androidx.compose.material.icons.filled.Colorize
import androidx.compose.material.icons.filled.Description
import androidx.compose.material.icons.filled.Download
import androidx.compose.material.icons.filled.Equalizer
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.FormatQuote
import androidx.compose.material.icons.filled.History
import androidx.compose.material.icons.filled.Notifications
import androidx.compose.material.icons.filled.Opacity
import androidx.compose.material.icons.filled.Palette
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Storage
import androidx.compose.material.icons.filled.Sync
import androidx.compose.material.icons.filled.TextFields
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.animation.AnimatedVisibility
import androidx.compose.animation.expandVertically
import androidx.compose.animation.shrinkVertically
import androidx.compose.animation.fadeIn
import androidx.compose.animation.fadeOut
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.platform.LocalFocusManager
import androidx.compose.ui.platform.LocalSoftwareKeyboardController
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.unit.IntSize
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.IconButton
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.lazy.items
import com.folio.reader.ui.library.FolioSearchField
import com.folio.reader.ui.components.FolioBackHandler
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioFieldBottom
import com.folio.reader.ui.components.folioTypeGround
import com.folio.reader.ui.components.FolioGlassPanel
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.folioBarTopInset

/**
 * The More hub, as a **table of contents** rather than nested boxes.
 *
 * The old screen put every group inside a `FolioSectionCard`, producing a
 * list-inside-a-box-inside-a-list — three levels of container for what is really
 * one document with five headings. Now:
 *
 *  - each group is titled by an eyebrow **on the page**, tinted with its own
 *    accent role, so the five categories have distinct identities;
 *  - rows are separated by hairline rules, not by card rims;
 *  - each icon sits on a small embedded tile in its section's accent, so the icon
 *    interacts with a surface instead of floating in a gutter;
 *  - sections breathe at `spaceMovement`.
 *
 * Every destination, label and tap target is unchanged.
 */
@Composable
fun SettingsHubScreen(
    onOpenSettings: (String) -> Unit,
    onOpenTags: () -> Unit,
    onOpenQuotes: () -> Unit,
    onOpenRevisit: () -> Unit,
    onOpenExtensions: () -> Unit,
    onOpenDownloads: () -> Unit,
    onOpenHistory: () -> Unit
) {
    var searchOpen by rememberSaveable { mutableStateOf(false) }
    var query by rememberSaveable { mutableStateOf("") }
    var searchRailHeight by remember { mutableIntStateOf(0) }
    val focus = LocalFocusManager.current
    val keyboard = LocalSoftwareKeyboardController.current
    val focusRequester = remember { FocusRequester() }
    val closeSearch: () -> Unit = { searchOpen = false; query = ""; focus.clearFocus(); keyboard?.hide() }
    FolioBackHandler(enabled = searchOpen, onBack = closeSearch)
    val headerState = rememberFolioHeaderState()
    // Re-tap on the More nav item scrolls the hub back to its top.
    val listState = androidx.compose.foundation.lazy.rememberLazyListState()
    LaunchedEffect(listState) {
        com.folio.reader.ui.components.FolioTabReselect.events.collect { (route, _) ->
            if (route == "more" &&
                (listState.firstVisibleItemIndex > 0 || listState.firstVisibleItemScrollOffset > 0)
            ) {
                listState.animateScrollToItem(0)
            }
        }
    }
    // The bar's at-rest height: status icons plus the bar row. Paid by the list
    // alone, and static — tracking the scrolled glass would translate every row
    // mid-scroll.
    LaunchedEffect(searchOpen) {
        headerState.reset()
        if (searchOpen) {
            listState.scrollToItem(0)
            withFrameNanos { }
            runCatching { focusRequester.requestFocus() }
        }
    }
    val searchInset = with(LocalDensity.current) { searchRailHeight.toDp() }
    val searchFade = rememberMotionAnimation<Float>(MotionKey.ReaderChromeToggle)
    val searchSize = rememberMotionAnimation<IntSize>(MotionKey.ReaderChromeToggle)
    val matches = remember(query) { searchSettings(query) }
    fun openSearchResult(entry: SettingsSearchEntry) {
        focus.clearFocus(); keyboard?.hide()
        when (entry.route) {
            "@tags" -> onOpenTags()
            "@quotes" -> onOpenQuotes()
            "@revisit" -> onOpenRevisit()
            "@extensions" -> onOpenExtensions()
            "@downloads" -> onOpenDownloads()
            "@history" -> onOpenHistory()
            else -> onOpenSettings(entry.route)
        }
    }
    val topInset = folioBarTopInset()
    val colors = FolioTheme.colors

    Box(modifier = Modifier.fillMaxSize()) {
        LazyColumn(
            state = listState,
            modifier = Modifier
                .fillMaxSize()
                .then(if (searchOpen) Modifier.imePadding() else Modifier)
                .nestedScroll(headerState.nestedScrollConnection)
                // §16: the hub list is the backdrop the "More" masthead and the
                // capsule blur.
                .folioBackdropSource().folioPageWash(),
            contentPadding = PaddingValues(
                start = FolioTokens.gutter,
                end = FolioTokens.gutter,
                top = topInset + searchInset + FolioTokens.space2,
                bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
            ),
        ) {
            if (searchOpen) {
                item(key = "settings-search-caption") {
                    Text(if (query.isBlank()) "Find a setting by name or what you want to change." else "${matches.size} matching ${if (matches.size == 1) "destination" else "destinations"}",
                        style = FolioTheme.typography.bodyMedium, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 16.dp))
                }
                items(matches, key = { "setting:${it.route}" }) { entry ->
                    HubRow(icon = settingsSearchIcon(entry.route), title = entry.title,
                        subtitle = "${entry.section} · ${entry.summary}", accent = colors.primary,
                        onClick = { openSearchResult(entry) })
                }
                if (matches.isEmpty()) item(key = "settings-no-match") {
                    Text("No matching settings. Try a word such as font, opacity, manga or sync.",
                        style = FolioTheme.typography.bodyLarge, color = colors.onSurfaceVariant,
                        modifier = Modifier.padding(vertical = 24.dp))
                }
            } else {
            item {
                HubSection("Reading", colors.accentProgress) {
                    HubRow(
                        icon = Icons.Filled.Equalizer,
                        title = "Reader defaults",
                        subtitle = "What a new book or manga starts with",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.DEFAULTS) }
                    )
                    HubRow(
                        icon = Icons.Filled.TextFields,
                        title = "Text & page",
                        subtitle = "Typeface, measure, alignment, hyphenation",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.TEXT_AND_PAGE) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "Reader behavior",
                        subtitle = "Chapter title, progress, clock, brightness",
                        accent = colors.accentProgress,
                        onClick = { onOpenSettings(FolioSettingsCategory.READING) }
                    )
                    HubRow(
                        icon = Icons.Filled.Description,
                        title = "Documents",
                        subtitle = "How PDFs and Word files are shown",
                        accent = colors.accentProgress,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.DOCUMENTS) }
                    )
                }
            }
            item {
                HubSection("Appearance", colors.accentDiscovery) {
                    HubRow(
                        icon = Icons.Filled.Palette,
                        title = "Themes",
                        subtitle = "Theme packs, typeface pairing, fonts",
                        accent = colors.accentDiscovery,
                        onClick = { onOpenSettings(FolioSettingsCategory.THEMES) }
                    )
                    HubRow(
                        icon = Icons.Filled.Colorize,
                        title = "Custom theme",
                        subtitle = "Author your own palette and gradient",
                        accent = colors.accentDiscovery,
                        onClick = { onOpenSettings(FolioSettingsCategory.CUSTOM_THEME) }
                    )
                    HubRow(
                        icon = Icons.Filled.Animation,
                        title = "Animations",
                        subtitle = "How much the app and the background move",
                        accent = colors.accentDiscovery,
                        onClick = { onOpenSettings(FolioSettingsCategory.ANIMATIONS) }
                    )
                    HubRow(
                        icon = Icons.Filled.Opacity,
                        title = "Transparency",
                        subtitle = "How solid bars, cards and reader controls are",
                        accent = colors.accentDiscovery,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.TRANSPARENCY) }
                    )
                }
            }
            item {
                HubSection("Sync & backup", colors.accentStreak) {
                    HubRow(
                        icon = Icons.Filled.Sync,
                        title = "Cloud sync",
                        subtitle = "Firebase project and sync preferences",
                        accent = colors.accentStreak,
                        onClick = { onOpenSettings(FolioSettingsCategory.CLOUD_SYNC) }
                    )
                    HubRow(
                        icon = Icons.Filled.Backup,
                        title = "Backup & data",
                        subtitle = "Backups, annotation export, storage",
                        accent = colors.accentStreak,
                        onClick = { onOpenSettings(FolioSettingsCategory.ADVANCED) }
                    )
                    HubRow(
                        icon = Icons.Filled.Storage,
                        title = "Storage",
                        subtitle = "What's using space, by category",
                        accent = colors.accentStreak,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.STORAGE) }
                    )
                }
            }
            item {
                HubSection("Library tools", colors.accentAnnotation) {
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.Label,
                        title = "Tags",
                        subtitle = "Organise books with tags",
                        accent = colors.accentAnnotation,
                        onClick = onOpenTags
                    )
                    HubRow(
                        icon = Icons.Filled.FormatQuote,
                        title = "Quotes",
                        subtitle = "Saved passages across books",
                        accent = colors.accentAnnotation,
                        onClick = onOpenQuotes
                    )
                    HubRow(
                        icon = Icons.Filled.History,
                        title = "Revisit",
                        subtitle = "Books worth returning to",
                        accent = colors.accentAnnotation,
                        onClick = onOpenRevisit
                    )
                    HubRow(
                        icon = Icons.Filled.Equalizer,
                title = "Exclusions",
                subtitle = "Keep titles out of Stats and Home",
                        accent = colors.accentAnnotation,
                        onClick = { onOpenSettings(FolioSettingsCategory.STATS) }
                    )
                    HubRow(
                        icon = Icons.Filled.Search,
                        title = "Library scanning",
                        subtitle = "Import new ebooks and documents automatically",
                        accent = colors.accentAnnotation,
                        onClick = { onOpenSettings(FolioSettingsCategory.LIBRARY_SCAN) }
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.ManageSearch,
                        title = "Semantic search",
                        subtitle = "Find passages by meaning, entirely on this device",
                        accent = colors.accentAnnotation,
                        last = true,
                        onClick = { onOpenSettings(FolioSettingsCategory.SEMANTIC_SEARCH) }
                    )
                }
            }
            item {
                HubSection("Manga", colors.tertiary) {
                    HubRow(icon = Icons.Filled.Search, title = "Discover",
                        subtitle = "Usual suggestions, selected extensions, or hidden",
                        accent = colors.tertiary,
                        onClick = { onOpenSettings(FolioSettingsCategory.DISCOVER) })
                    HubRow(
                        icon = Icons.Filled.Notifications,
                        title = "Manga updates",
                        subtitle = "Background chapter checks",
                        accent = colors.tertiary,
                        onClick = { onOpenSettings(FolioSettingsCategory.MANGA) }
                    )
                    HubRow(
                        icon = Icons.Filled.Extension,
                        title = "Extensions",
                        subtitle = "Browse and manage manga sources",
                        accent = colors.tertiary,
                        onClick = onOpenExtensions
                    )
                    HubRow(
                        icon = Icons.Filled.Download,
                        title = "Downloads",
                        subtitle = "Downloaded chapters and storage",
                        accent = colors.tertiary,
                        onClick = onOpenDownloads
                    )
                    HubRow(
                        icon = Icons.AutoMirrored.Filled.MenuBook,
                        title = "History",
                        subtitle = "Recently read chapters",
                        accent = colors.tertiary,
                        last = true,
                        onClick = onOpenHistory
                    )
                }
            }
            }
        }

        // Last, so it is on top of the list rather than a sibling above it: the
        // rows now pass under the glass, which is the only thing that makes a
        // translucent bar read as a bar.
        FolioTopBar(
            title = "More",
            collapse = if (searchOpen) 0f else headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            actions = {
                IconButton(onClick = { if (searchOpen) closeSearch() else searchOpen = true }) {
                    Icon(if (searchOpen) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchOpen) "Close settings search" else "Search settings")
                }
            },
            rail = {
                AnimatedVisibility(searchOpen,
                    enter = expandVertically(searchSize, expandFrom = Alignment.Top) + fadeIn(searchFade),
                    exit = shrinkVertically(searchSize, shrinkTowards = Alignment.Top) + fadeOut(searchFade),
                    modifier = Modifier.fillMaxWidth().onSizeChanged { searchRailHeight = it.height }) {
                    Box(Modifier.fillMaxWidth().padding(horizontal = FolioTokens.gutter, vertical = 12.dp)) {
                        FolioSearchField(query, { query = it }, "Search settings",
                            Modifier.fillMaxWidth().focusRequester(focusRequester))
                    }
                }
            },
        )
    }
}

/** A titled group: accent eyebrow on the page, rows on a resting panel so the group reads
 *  as a surface instead of fading into the wallpaper, a real gap after. */
@Composable
private fun HubSection(
    title: String,
    accent: Color,
    content: @Composable () -> Unit,
) {
    Column(modifier = Modifier.padding(bottom = FolioTokens.spaceMovement)) {
        // Issue 4: these kickers ("APPEARANCE", "SYNC & BACKUP", "FEATURED") sit on bare
        // field, outside the veil below them, and the field is a warm animated Mirage — so
        // an accent-coloured kicker over a bloom of the same hue was orange on orange and
        // effectively invisible. Two additive fixes, both reusing what already exists:
        //
        //  - `folioTypeGround` gives the label the page's own lower ramp colour at under
        //    half strength, dissolved at head and foot, exactly as the shelf captions get
        //    it. Full width, so the wash has no side edge to read as the lip of a card —
        //    a kicker must not become a chip.
        //  - `background = folioFieldBottom()` fixes the contrast guard inside FolioEyebrow,
        //    which blends an accent toward the field-secondary ink until it clears 4.5:1
        //    *against the ground it is passed*. It defaulted to `colors.surface` — a plane
        //    this label is nowhere near — so the guard was measuring the wrong ground and
        //    passing an accent that the real field never supported.
        FolioEyebrow(
            title,
            modifier = Modifier.fillMaxWidth().folioTypeGround(),
            accent = accent,
            background = folioFieldBottom(),
        )
        Spacer(Modifier.height(FolioTokens.space2))
        FolioGlassPanel(
            contentPadding = PaddingValues(horizontal = FolioTokens.space3, vertical = FolioTokens.space1),
            verticalArrangement = androidx.compose.foundation.layout.Arrangement.spacedBy(0.dp),
        ) { content() }
    }
}

/**
 * One navigation row. The icon sits on an embedded tile tinted by its section's
 * accent — icon and surface belong to each other, instead of a bare glyph in a
 * gutter. A hairline closes the row unless it is the section's last.
 */
@Composable
private fun HubRow(
    icon: ImageVector,
    title: String,
    subtitle: String,
    accent: Color,
    last: Boolean = false,
    onClick: () -> Unit
) {
    val colors = FolioTheme.colors
    val interaction = rememberFolioInteraction()
    Column {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .folioPressable(interaction, scaleTo = 0.99f)
                .clickable(interactionSource = interaction, indication = null, onClick = onClick)
                .padding(vertical = FolioTokens.space2),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            Box(
                modifier = Modifier
                    .size(38.dp)
                    .clip(FolioShapes.inset)
                    .background(accent.copy(alpha = .09f))
                    .border(.7.dp, accent.copy(alpha = .16f), FolioShapes.inset),
                contentAlignment = Alignment.Center,
            ) {
                Icon(
                    icon,
                    contentDescription = null,
                    tint = accent,
                    modifier = Modifier.size(19.dp),
                )
            }
            Spacer(Modifier.width(FolioTokens.space3))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    title,
                    style = FolioTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                )
                Text(
                    subtitle,
                    style = FolioTheme.typography.bodySmall,
                    color = colors.onSurfaceVariant,
                    maxLines = 2,
                    overflow = TextOverflow.Ellipsis,
                )
            }
            Spacer(Modifier.width(FolioTokens.space2))
            Icon(
                Icons.AutoMirrored.Filled.KeyboardArrowRight,
                contentDescription = null,
                tint = colors.onSurfaceVariant.copy(alpha = 0.6f),
                modifier = Modifier.size(18.dp),
            )
        }
        if (!last) FolioRule()
    }
}

private fun settingsSearchIcon(route: String): ImageVector = when (route) {
    FolioSettingsCategory.TEXT_AND_PAGE -> Icons.Filled.TextFields
    FolioSettingsCategory.TRANSPARENCY -> Icons.Filled.Opacity
    FolioSettingsCategory.THEMES, FolioSettingsCategory.CUSTOM_THEME -> Icons.Filled.Palette
    FolioSettingsCategory.ANIMATIONS -> Icons.Filled.Animation
    FolioSettingsCategory.CLOUD_SYNC -> Icons.Filled.Sync
    FolioSettingsCategory.STORAGE -> Icons.Filled.Storage
    FolioSettingsCategory.ADVANCED -> Icons.Filled.Backup
    FolioSettingsCategory.DOCUMENTS -> Icons.Filled.Description
    "@tags" -> Icons.AutoMirrored.Filled.Label
    "@quotes" -> Icons.Filled.FormatQuote
    "@revisit", "@history" -> Icons.Filled.History
    "@extensions" -> Icons.Filled.Extension
    "@downloads" -> Icons.Filled.Download
    FolioSettingsCategory.MANGA -> Icons.Filled.Notifications
    FolioSettingsCategory.DISCOVER, FolioSettingsCategory.SEMANTIC_SEARCH, FolioSettingsCategory.LIBRARY_SCAN -> Icons.Filled.Search
    else -> Icons.Filled.Equalizer
}
