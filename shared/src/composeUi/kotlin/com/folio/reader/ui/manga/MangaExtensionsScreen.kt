package com.folio.reader.ui.manga

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.selection.selectable
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Extension
import androidx.compose.material.icons.filled.MoreVert
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material.icons.filled.Search
import com.folio.reader.ui.components.FolioActionButton as Button
import androidx.compose.material3.CircularProgressIndicator
import com.folio.reader.ui.components.FolioMenuItem as DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import com.folio.reader.ui.components.FolioInputField as OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.input.nestedscroll.nestedScroll
import androidx.compose.ui.layout.onSizeChanged
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.ui.components.EmptyState
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioEyebrow
import com.folio.reader.ui.components.FolioRowListSkeleton
import com.folio.reader.ui.components.FolioRule
import com.folio.reader.ui.components.FolioSectionCard
import com.folio.reader.ui.components.FolioHubHeading
import com.folio.reader.ui.components.FolioHubRow
import com.folio.reader.ui.components.FolioHubSection
import com.folio.reader.ui.components.FolioHubGroupItem
import com.folio.reader.ui.components.FolioHubSkeleton
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.folioBackdropSource
import com.folio.reader.ui.components.folioPressable
import com.folio.reader.ui.components.folioVeil
import com.folio.reader.ui.components.rememberFolioHeaderState
import com.folio.reader.ui.components.rememberFolioInteraction
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.components.folioPageWash
import com.folio.reader.ui.library.FolioSearchField
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.LocalFolioBarInset
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioBarTopInset
import com.folio.reader.ui.theme.sectionAccent

@Composable
fun ExtensionsScreen(
    viewModel: BrowseViewModel,
    onBack: () -> Unit,
) {
    val extensions by viewModel.extensions.collectAsState()
    val installStates by viewModel.installStates.collectAsState()
    val repos by viewModel.repos.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    val repoErrors by viewModel.extensionRepoErrors.collectAsState()
    val loadErrors by viewModel.extensionLoadErrors.collectAsState()
    val trustError by viewModel.trustError.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0 installed, 1 available, 2 untrusted
    var showAddRepo by remember { mutableStateOf(false) }

    val nsfw by viewModel.nsfw.collectAsState()
    val visibleExt = extensions.filter { nsfw || !it.isNsfw }
    val installed = visibleExt.filter { it.isInstalled }
    val untrusted = visibleExt.filter { it.isUntrusted }
    val available = visibleExt.filter { !it.isInstalled && !it.isUntrusted }
    var extQuery by remember { mutableStateOf("") }
    // Search takes the rail's single row over from the tabs rather than stacking a
    // second row under them — searching already ignores the tab (see `shown`), so
    // the two controls are never both relevant at once.
    var searchActive by remember { mutableStateOf(false) }
    var overflowOpen by remember { mutableStateOf(false) }
    // Package the user just tapped Install on, so the trust prompt can pop the
    // moment that extension lands as untrusted (see LaunchedEffect below).
    var justInstalledPkg by remember { mutableStateOf<String?>(null) }
    // The extension whose trust confirmation is currently shown at screen level.
    var pendingTrust by remember { mutableStateOf<ExtensionEntry?>(null) }

    LaunchedEffect(Unit) {
        if (viewModel.supportsExtensions && available.isEmpty()) {
            viewModel.refreshIndex()
        }
    }
    LaunchedEffect(extensions) {
        if (installed.isEmpty() && available.isNotEmpty() && tab == 0) tab = 1
        // As soon as a just-installed extension finishes and lands as untrusted,
        // surface its trust prompt immediately so it can be enabled without hunting
        // for the Untrusted tab. Only the extension the user just installed triggers
        // this, so the one-time bulk re-trust after upgrading never spams dialogs.
        val pkg = justInstalledPkg
        if (pkg != null) {
            when (val entry = extensions.firstOrNull { it.pkgName == pkg }) {
                null -> Unit
                else -> if (entry.isUntrusted) {
                    pendingTrust = entry
                    justInstalledPkg = null
                } else if (entry.isInstalled) {
                    justInstalledPkg = null
                }
            }
        }
    }

    val shownBase = when (tab) {
        0 -> installed
        1 -> available
        else -> untrusted
    }
    val shown = if (extQuery.isBlank()) shownBase
        else visibleExt
            .distinctBy { it.pkgName }
            .filter { it.name.contains(extQuery, ignoreCase = true) }

    val headerState = rememberFolioHeaderState()
    // One masthead, not two.
    //
    // The tabs, the NSFW switch and the search field used to hang below the bar as a
    // second full-bleed `folioVeil` strip. Two translucent plates stacked edge to edge
    // do not read as glass: their fills add up, so the top of the screen came out as a
    // flat slab with a seam across it — the reported "top bar is opaque", and the band
    // under it reading as a disconnected plate. They are the bar's own `rail` now, so
    // the chrome is a single surface with a single fill, and the rail folds up under
    // the title on scroll instead of staying pinned as a separate lid.
    //
    // Measured rather than predicted (the chip row and the search field are different
    // heights), the same way Library measures its rail: the reading is what the list's
    // top padding clears, via `folioBarTopInset`.
    var railPx by remember { mutableStateOf(0) }
    val topInset = folioBarTopInset(with(LocalDensity.current) { railPx.toDp() })

    Box(Modifier.fillMaxSize()) {
        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .nestedScroll(headerState.nestedScrollConnection)
                .folioBackdropSource().folioPageWash(),
            contentPadding = PaddingValues(
                start = FolioTokens.gutter,
                top = topInset + FolioTokens.space2,
                end = FolioTokens.gutter,
                bottom = FolioTokens.spaceMovement + LocalFolioBarInset.current,
            ),
            verticalArrangement = Arrangement.spacedBy(0.dp),
        ) {
            val problems = listOfNotNull(trustError) + repoErrors + loadErrors.map { "Extension could not load: $it" }
            if (problems.isNotEmpty()) item(key = "extension-problems") {
                FolioHubSection("Needs attention", FolioTheme.colors.error) {
                    problems.forEach { problem ->
                        Text(problem, style = FolioTheme.typography.bodySmall, color = FolioTheme.colors.error,
                            modifier = Modifier.padding(vertical = FolioTokens.space2))
                    }
                }
            }
            // Fetching a repository index is the slowest thing this screen does, and
            // the toolbar's 20dp spinner is easy to miss. Rows stand in at their real
            // height so the wait reads as a list arriving, not as an empty tab.
            if (shown.isEmpty() && refreshing) {
                item(key = "ext-skeleton") {
                    FolioHubSkeleton("Extensions", rows = 7)
                }
            }
            // Every blank condition goes through the app's one blank-state layout
            // rather than a bare line of body text dropped at the top of the page.
            if (shown.isEmpty() && !refreshing && viewModel.supportsExtensions) {
                item(key = "ext-empty") {
                    EmptyState(
                        icon = Icons.Filled.Extension,
                        headline = when {
                            extQuery.isNotBlank() -> "Nothing matches that"
                            tab == 0 -> "No extensions installed"
                            tab == 2 -> "Nothing waiting to be trusted"
                            else -> "No extension index yet"
                        },
                        body = when {
                            extQuery.isNotBlank() -> "No extension name contains “$extQuery”."
                            tab == 0 -> "Install one from Available to add a source."
                            tab == 2 -> "An extension you install waits here until you trust it."
                            else -> "Tap refresh to fetch the repository index."
                        },
                    )
                }
            }
            val groups = shown.groupBy { it.lang ?: "all" }.toList()
                .sortedWith(compareByDescending<Pair<String, List<ExtensionEntry>>> { it.first == "en" }
                    .thenBy { languageLabel(it.first) })
            groups.forEachIndexed { groupIndex, (lang, entries) ->
                item(key = "extension-language-$lang") {
                    BrowseGroupHeading(languageLabel(lang), first = groupIndex == 0)
                }
                itemsIndexed(entries, key = { _, entry -> entry.pkgName }) { index, entry ->
                    FolioHubGroupItem(index == 0, index == entries.lastIndex) {
                        ExtensionRow(entry = entry, installStep = installStates[entry.pkgName], last = index == entries.lastIndex,
                            onInstall = { justInstalledPkg = entry.pkgName; viewModel.install(entry.pkgName) },
                            onUpdate = { viewModel.update(entry.pkgName) }, onUninstall = { viewModel.uninstall(entry.pkgName) },
                            onTrust = { viewModel.trust(entry) })
                    }
                }
            }
            item(key = "ext-repos") {
                Spacer(Modifier.height(FolioTokens.spaceMovement))
                if (viewModel.supportsExtensions) {
                    FolioHubSection("Repositories", FolioTheme.colors.tertiary) {
                        repos.forEach { repo ->
                            FolioHubRow(Icons.Filled.Extension, repo.name, repo.baseUrl, FolioTheme.colors.tertiary, trailingInline = true,
                                trailing = {
                                    IconButton(onClick = { viewModel.saveRepos(repos.filterNot { it.baseUrl == repo.baseUrl }) }) {
                                        Icon(Icons.Filled.Delete, "Remove ${repo.name}", Modifier.size(19.dp), FolioTheme.colors.onSurfaceVariant)
                                    }
                                })
                        }
                        if (repos.isEmpty()) Text("Add a repository to see extensions available to install.",
                            style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant,
                            modifier = Modifier.padding(vertical = FolioTokens.space2))
                        FolioHubRow(Icons.Filled.Add, "Add repository", "Add a catalog of manga sources", FolioTheme.colors.tertiary,
                            last = true, onClick = { showAddRepo = true })
                    }
                } else Text("Extensions run on Android. Read imported CBZ, ZIP or image folders on this device.",
                    style = FolioTheme.typography.bodyMedium, color = FolioTheme.colors.onSurfaceVariant)
            }

        }

        FolioTopBar(
            title = "Extensions",
            // Pinned while a search is open, the same contract Library's masthead
            // keeps: the rail folds on scroll, and a field that folded away mid-query
            // would hide the one thing explaining why the list is filtered.
            collapse = if (searchActive) 0f else headerState.collapse,
            modifier = Modifier.align(Alignment.TopCenter),
            bottomRule = false,
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(
                    onClick = {
                        if (searchActive) {
                            searchActive = false
                            // Leaving search restores the tab's own list; a query left
                            // behind would silently keep overriding it.
                            extQuery = ""
                        } else {
                            searchActive = true
                        }
                    },
                ) {
                    Icon(
                        if (searchActive) Icons.Filled.Close else Icons.Filled.Search,
                        contentDescription = if (searchActive) "Close search" else "Search extensions",
                    )
                }
                if (viewModel.supportsExtensions) {
                    // NSFW is a stored preference, not a filter the reader flips while
                    // reading a list, so it belongs in the bar's menu — the same place
                    // and the same checked-item idiom the source-language filter uses —
                    // rather than as a raw Material checkbox row occupying the masthead.
                    Box {
                        IconButton(onClick = { overflowOpen = true }) {
                            Icon(Icons.Filled.MoreVert, contentDescription = "More")
                        }
                        com.folio.reader.ui.components.FolioMenu(
                            expanded = overflowOpen,
                            onDismissRequest = { overflowOpen = false },
                        ) {
                            DropdownMenuItem(text = { Text(if (refreshing) "Refreshing catalog…" else "Refresh catalog") },
                                enabled = !refreshing, onClick = { viewModel.refreshIndex(); overflowOpen = false })
                            DropdownMenuItem(
                                text = {
                                    Text(
                                        "Show NSFW extensions",
                                        color = if (nsfw) FolioTheme.colors.primary else FolioTheme.colors.onSurface,
                                    )
                                },
                                leadingIcon = {
                                    if (nsfw) {
                                        Icon(
                                            Icons.Filled.Check,
                                            contentDescription = null,
                                            tint = FolioTheme.colors.primary,
                                            modifier = Modifier.size(18.dp),
                                        )
                                    } else {
                                        Spacer(Modifier.size(18.dp))
                                    }
                                },
                                onClick = {
                                    viewModel.setNsfw(!nsfw)
                                    overflowOpen = false
                                },
                            )
                        }
                    }
                }
            },
            rail = {
                Box(modifier = Modifier.onSizeChanged { railPx = it.height }) {
                    ExtensionsRail(
                        searchActive = searchActive,
                        query = extQuery,
                        onQueryChange = { extQuery = it },
                        tab = tab,
                        onTabChange = { tab = it },
                    )
                }
            },
        )
    }

    if (showAddRepo) {
        AddRepoDialog(
            onAdd = { name, baseUrl, indexUrl ->
                viewModel.saveRepos(
                    repos + MangaRepoInfo(
                        name = name.ifBlank { baseUrl },
                        baseUrl = baseUrl,
                        indexUrl = indexUrl.ifBlank { baseUrl.trimEnd('/') + "/index.min.json" },
                    )
                )
                showAddRepo = false
            },
            onDismiss = { showAddRepo = false },
        )
    }

    // Auto-surfaced trust prompt for a just-installed extension, so it can be
    // enabled right away instead of via the Untrusted tab.
    pendingTrust?.let { entry ->
        TrustExtensionDialog(
            entry = entry,
            onConfirm = {
                viewModel.trust(entry)
                pendingTrust = null
            },
            onDismiss = { pendingTrust = null },
        )
    }
}

/**
 * The one row this screen hangs under its title.
 *
 * Deliberately one row and not a stack: the bar's `rail` contract is a single row
 * (a mode row over a filter row over a field is what made Library read as a
 * control panel), and the two controls here are mutually exclusive anyway —
 * a non-blank query searches every tab, so the tabs have nothing to say while it
 * is being typed. Both branches are floored at the same 48dp so swapping between
 * them does not walk the list up and down under the reader's finger.
 */
@Composable
internal fun ExtensionsRail(
    searchActive: Boolean,
    query: String,
    onQueryChange: (String) -> Unit,
    tab: Int,
    onTabChange: (Int) -> Unit,
) {
    if (searchActive) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .heightIn(min = 48.dp)
                .padding(horizontal = FolioTokens.gutter),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            // The library/manga rail's field, not a Material OutlinedTextField: the
            // same pill, hairline and sunken fill the chips beside it wear, so the
            // masthead stays one vocabulary. It carries its own clear affordance, and
            // leaving search is the bar's own action, so the row needs nothing else.
            FolioSearchField(
                query = query,
                onQueryChange = onQueryChange,
                placeholder = "Search extensions",
                modifier = Modifier.weight(1f),
            )
        }
    } else {
        androidx.compose.foundation.layout.BoxWithConstraints(Modifier.fillMaxWidth().padding(horizontal = FolioTokens.gutter)) {
            val tabWidth = maxOf(maxWidth / 3, 60.dp * LocalDensity.current.fontScale)
            LazyRow(modifier = Modifier.fillMaxWidth().heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            listOf("Installed", "Available", "Untrusted")
                .forEachIndexed { index, label ->
                    item(key = label) {
                        val selected = tab == index
                        val accent = FolioTheme.colors.tertiary
                        Box(Modifier.width(tabWidth).heightIn(min = 48.dp)
                            .selectable(selected = selected, role = androidx.compose.ui.semantics.Role.Tab,
                                onClick = { onTabChange(index) })
                            .drawBehind {
                                if (selected) drawLine(accent, Offset(size.width * .2f, size.height - 2.dp.toPx()),
                                    Offset(size.width * .8f, size.height - 2.dp.toPx()), 2.dp.toPx(), androidx.compose.ui.graphics.StrokeCap.Round)
                            }.padding(vertical = 12.dp), contentAlignment = Alignment.Center) {
                            Text(label, style = FolioTheme.typography.labelMedium,
                                color = if (selected) accent else FolioTheme.colors.onSurfaceVariant)
                        }
                    }
                }
            }
        }
    }
}

@Composable
internal fun ExtensionRow(
    entry: ExtensionEntry,
    installStep: ExtensionInstallStep?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onTrust: () -> Unit,
    last: Boolean = true,
) {
    val colors = FolioTheme.colors
    var showTrustConfirm by remember { mutableStateOf(false) }
    var menuOpen by remember { mutableStateOf(false) }
    val busy = installStep in setOf(ExtensionInstallStep.Pending, ExtensionInstallStep.Downloading, ExtensionInstallStep.Installing)
    val status = when {
        installStep == ExtensionInstallStep.Error -> "Install failed"
        installStep == ExtensionInstallStep.Downloading -> "Downloading"
        installStep == ExtensionInstallStep.Installing -> "Installing"
        installStep == ExtensionInstallStep.Pending -> "Queued"
        entry.isUntrusted -> "Needs your trust"
        entry.hasUpdate -> "Update available"
        entry.isInstalled -> "Installed"
        else -> "Available"
    }
    val subtitle = listOfNotNull(status, "v${entry.versionName}", if (entry.isNsfw) "18+" else null,
        entry.sourceNames.filterNot { it.equals(entry.name, ignoreCase = true) }.joinToString(", ").takeIf { it.isNotBlank() })
        .joinToString(" · ")
    FolioHubRow(Icons.Filled.Extension, entry.name, subtitle, colors.tertiary, last = last,
        trailingInline = entry.isInstalled && !entry.hasUpdate,
        trailing = {
            Row(Modifier.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
                when {
                    busy -> CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp, color = colors.tertiary)
                    entry.isUntrusted -> TextButton(onClick = { showTrustConfirm = true }) { Text("Review", color = colors.tertiary) }
                    installStep == ExtensionInstallStep.Error -> TextButton(onClick = onInstall) { Text("Retry", color = colors.tertiary) }
                    entry.isInstalled && entry.hasUpdate -> TextButton(onClick = onUpdate) { Text("Update", color = colors.tertiary) }
                    !entry.isInstalled -> TextButton(onClick = onInstall) { Text("Install", color = colors.tertiary) }
                }
                if (entry.isInstalled && !busy) Box {
                    IconButton(onClick = { menuOpen = true }) {
                        Icon(Icons.Filled.MoreVert, "Options for ${entry.name}", Modifier.size(19.dp), colors.onSurfaceVariant)
                    }
                    com.folio.reader.ui.components.FolioMenu(menuOpen, { menuOpen = false }) {
                        com.folio.reader.ui.components.FolioMenuItem(text = { Text("Uninstall") },
                            onClick = { menuOpen = false; onUninstall() })
                    }
                }
            }
        })

    if (showTrustConfirm) {
        TrustExtensionDialog(
            entry = entry,
            onConfirm = {
                showTrustConfirm = false
                onTrust()
            },
            onDismiss = { showTrustConfirm = false },
        )
    }
}

@Composable
private fun TrustExtensionDialog(
    entry: ExtensionEntry,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit,
) {
    // §16 P5: the app's own in-window pane rather than Material's `AlertDialog` (see FolioDialog).
    com.folio.reader.ui.components.FolioDialog(
        expanded = true,
        onDismissRequest = onDismiss,
        title = "Trust this extension?",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
                Text(
                    "Extensions are third-party code that runs inside Folio. Only trust an " +
                        "extension you recognize -- a matching repository key is not proof of safety.",
                    style = FolioTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurface,
                )
                Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space1)) {
                    Text(
                        entry.name,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                    )
                    Text(
                        "Package: ${entry.pkgName}",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                    Text(
                        "Version: ${entry.versionName}",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                    Text(
                        "Signature: ${entry.signatureHash}",
                        style = FolioTheme.typography.bodySmall,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(onClick = onConfirm) { Text("Trust") }
        }
    }
}

@Composable
private fun AddRepoDialog(onAdd: (name: String, baseUrl: String, indexUrl: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var indexUrl by remember { mutableStateOf("") }

    // §16 P5: the app's own in-window pane rather than Material's `AlertDialog` (see FolioDialog).
    com.folio.reader.ui.components.FolioDialog(
        expanded = true,
        onDismissRequest = onDismiss,
        title = "Add extension repository",
    ) {
        Column(verticalArrangement = Arrangement.spacedBy(FolioTokens.space2)) {
            OutlinedTextField(value = name, onValueChange = { name = it }, label = { Text("Name") }, singleLine = true)
            OutlinedTextField(
                value = baseUrl,
                onValueChange = { baseUrl = it },
                label = { Text("Base URL") },
                singleLine = true,
            )
            OutlinedTextField(
                value = indexUrl,
                onValueChange = { indexUrl = it },
                label = { Text("Index URL (optional)") },
                singleLine = true,
            )
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 12.dp),
            horizontalArrangement = Arrangement.End,
        ) {
            TextButton(onClick = onDismiss) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            Button(
                onClick = { onAdd(name.trim(), baseUrl.trim(), indexUrl.trim()) },
                enabled = baseUrl.isNotBlank(),
            ) { Text("Add") }
        }
    }
}
