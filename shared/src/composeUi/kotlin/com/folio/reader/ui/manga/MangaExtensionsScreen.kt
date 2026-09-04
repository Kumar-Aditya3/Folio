package com.folio.reader.ui.manga

import androidx.compose.foundation.background
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
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Delete
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Checkbox
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
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
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.manga.ExtensionEntry
import com.folio.reader.manga.ExtensionInstallStep
import com.folio.reader.manga.MangaRepoInfo
import com.folio.reader.ui.components.FolioChip
import com.folio.reader.ui.components.FolioRowListSkeleton
import com.folio.reader.ui.components.FolioTopBar
import com.folio.reader.ui.components.glassPanel
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens

@Composable
fun ExtensionsScreen(
    viewModel: BrowseViewModel,
    onBack: () -> Unit,
) {
    val extensions by viewModel.extensions.collectAsState()
    val installStates by viewModel.installStates.collectAsState()
    val repos by viewModel.repos.collectAsState()
    val refreshing by viewModel.refreshingIndex.collectAsState()
    var tab by remember { mutableStateOf(0) } // 0 installed, 1 available, 2 untrusted
    var showAddRepo by remember { mutableStateOf(false) }

    val nsfw by viewModel.nsfw.collectAsState()
    val visibleExt = extensions.filter { nsfw || !it.isNsfw }
    val installed = visibleExt.filter { it.isInstalled }
    val untrusted = visibleExt.filter { it.isUntrusted }
    val available = visibleExt.filter { !it.isInstalled && !it.isUntrusted }
    var extQuery by remember { mutableStateOf("") }

    LaunchedEffect(Unit) {
        if (viewModel.supportsExtensions && available.isEmpty()) {
            viewModel.refreshIndex()
        }
    }
    LaunchedEffect(extensions) {
        if (installed.isEmpty() && available.isNotEmpty() && tab == 0) tab = 1
    }

    val shownBase = when (tab) {
        0 -> installed
        1 -> available
        else -> untrusted
    }
    val shown = if (extQuery.isBlank()) shownBase
        else extensions
            .distinctBy { it.pkgName }
            .filter { it.name.contains(extQuery, ignoreCase = true) }

    Column(Modifier.fillMaxSize()) {
        FolioTopBar(
            title = "Extensions",
            navigationIcon = {
                IconButton(onClick = onBack) { Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "Back") }
            },
            actions = {
                IconButton(onClick = { viewModel.refreshIndex() }) {
                    if (refreshing) {
                        CircularProgressIndicator(Modifier.size(20.dp), strokeWidth = 2.dp)
                    } else {
                        Icon(Icons.Filled.Refresh, contentDescription = "Refresh")
                    }
                }
            },
        )

        LazyRow(
            contentPadding = PaddingValues(horizontal = FolioTokens.space3),
            horizontalArrangement = Arrangement.spacedBy(FolioTokens.space1),
        ) {
            item { FolioChip(selected = tab == 0, onClick = { tab = 0 }, label = "Installed (${installed.size})") }
            item { FolioChip(selected = tab == 1, onClick = { tab = 1 }, label = "Available (${available.size})") }
            item { FolioChip(selected = tab == 2, onClick = { tab = 2 }, label = "Untrusted (${untrusted.size})") }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        if (viewModel.supportsExtensions) {
            val nsfw by viewModel.nsfw.collectAsState()
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .clickable { viewModel.setNsfw(!nsfw) }
                    .padding(horizontal = FolioTokens.space3),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Checkbox(checked = nsfw, onCheckedChange = { viewModel.setNsfw(it) })
                Text(
                    "Show NSFW extensions",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurface,
                )
            }
        }
        Spacer(Modifier.height(FolioTokens.space1))
        OutlinedTextField(
            value = extQuery,
            onValueChange = { extQuery = it },
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = FolioTokens.space3),
            placeholder = { Text("Search extensions") },
            singleLine = true,
        )
        Spacer(Modifier.height(FolioTokens.space2))

        LazyColumn(
            modifier = Modifier.weight(1f),
            contentPadding = PaddingValues(FolioTokens.space3),
            verticalArrangement = Arrangement.spacedBy(FolioTokens.space2),
        ) {
            if (shown.isEmpty() && tab == 1 && !refreshing) {
                item {
                    Text(
                        "No extension index loaded yet. Tap refresh to fetch the repository index.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
            // Fetching a repository index is the slowest thing this screen does, and
            // the toolbar's 20dp spinner is easy to miss. Rows stand in at their real
            // height so the wait reads as a list arriving, not as an empty tab.
            if (shown.isEmpty() && refreshing) {
                item(key = "ext-skeleton") {
                    FolioRowListSkeleton(rows = 7)
                }
            }
            val shownEn = shown.filter { it.lang == "en" }
            val shownOther = shown.filter { it.lang != "en" }
            if (shownEn.isNotEmpty()) {
                item(key = "ext-hdr-en") {
                    Text(
                        "ENGLISH",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                    )
                }
            }
            items(shownEn, key = { it.pkgName }) { entry ->
                ExtensionRow(
                    entry = entry,
                    installStep = installStates[entry.pkgName],
                    onInstall = { viewModel.install(entry.pkgName) },
                    onUpdate = { viewModel.update(entry.pkgName) },
                    onUninstall = { viewModel.uninstall(entry.pkgName) },
                    onTrust = { viewModel.trust(entry) },
                )
            }
            if (shownOther.isNotEmpty()) {
                item(key = "ext-hdr-other") {
                    Text(
                        "OTHER LANGUAGES",
                        style = MaterialTheme.typography.labelSmall,
                        color = FolioTheme.colors.primary,
                        modifier = Modifier.padding(top = FolioTokens.space2),
                    )
                }
            }
            items(shownOther, key = { it.pkgName }) { entry ->
                ExtensionRow(
                    entry = entry,
                    installStep = installStates[entry.pkgName],
                    onInstall = { viewModel.install(entry.pkgName) },
                    onUpdate = { viewModel.update(entry.pkgName) },
                    onUninstall = { viewModel.uninstall(entry.pkgName) },
                    onTrust = { viewModel.trust(entry) },
                )
            }
            item {
                Spacer(Modifier.height(FolioTokens.space2))
                if (viewModel.supportsExtensions) {
                    Text("REPOSITORIES", style = MaterialTheme.typography.labelSmall, color = FolioTheme.colors.primary)
                } else {
                    Text(
                        "Extensions run on Android only. On this device you can read local manga " +
                            "(CBZ/ZIP archives and image folders) imported into the library.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant,
                    )
                }
            }
            if (viewModel.supportsExtensions) {
                items(repos, key = { it.baseUrl }) { repo ->
                Row(
                    modifier = Modifier
                        .fillMaxWidth()
                        .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
                        .padding(FolioTokens.space3),
                    verticalAlignment = Alignment.CenterVertically,
                ) {
                    Column(Modifier.weight(1f)) {
                        Text(repo.name, style = MaterialTheme.typography.titleSmall, color = FolioTheme.colors.onSurface)
                        Text(
                            repo.baseUrl,
                            style = MaterialTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                    IconButton(onClick = { viewModel.saveRepos(repos.filterNot { it.baseUrl == repo.baseUrl }) }) {
                        Icon(Icons.Filled.Delete, contentDescription = "Remove repo", tint = FolioTheme.colors.error)
                    }
                }
            }
            item {
                OutlinedButton(onClick = { showAddRepo = true }) {
                    Icon(Icons.Filled.Add, contentDescription = null)
                    Spacer(Modifier.width(6.dp))
                    Text("Add repository")
                }
                }
            }
        }
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
}

@Composable
private fun ExtensionRow(
    entry: ExtensionEntry,
    installStep: ExtensionInstallStep?,
    onInstall: () -> Unit,
    onUpdate: () -> Unit,
    onUninstall: () -> Unit,
    onTrust: () -> Unit,
) {
    val colors = FolioTheme.colors
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(FolioTokens.radiusControl))
            .padding(FolioTokens.space3),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        Column(Modifier.weight(1f)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Text(
                    entry.name,
                    style = MaterialTheme.typography.titleSmall,
                    color = colors.onSurface,
                    maxLines = 1,
                    overflow = TextOverflow.Ellipsis,
                    modifier = Modifier.weight(1f, fill = false),
                )
                if (entry.isNsfw) {
                    Spacer(Modifier.width(6.dp))
                    Box(
                        modifier = Modifier
                            .background(colors.errorContainer, RoundedCornerShape(6.dp))
                            .padding(horizontal = 6.dp, vertical = 1.dp),
                    ) {
                        Text("18+", style = MaterialTheme.typography.labelMedium, color = colors.onErrorContainer)
                    }
                }
            }
            Text(
                listOfNotNull(
                    "v${entry.versionName}",
                    entry.lang?.uppercase(),
                    if (entry.sourceNames.isNotEmpty()) entry.sourceNames.joinToString(", ") else null,
                ).joinToString(" • "),
                style = MaterialTheme.typography.bodySmall,
                color = colors.onSurfaceVariant,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        Spacer(Modifier.width(FolioTokens.space2))
        when {
            entry.isUntrusted -> Button(onClick = onTrust) { Text("Trust") }
            installStep == ExtensionInstallStep.Downloading -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            installStep == ExtensionInstallStep.Installing -> CircularProgressIndicator(Modifier.size(22.dp), strokeWidth = 2.dp)
            entry.isInstalled && entry.hasUpdate -> {
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = colors.error)
                }
                Button(onClick = onUpdate) { Text("Update") }
            }
            entry.isInstalled -> {
                IconButton(onClick = onUninstall) {
                    Icon(Icons.Filled.Delete, contentDescription = "Uninstall", tint = colors.error)
                }
            }
            else -> Button(onClick = onInstall) { Text("Install") }
        }
    }
}

@Composable
private fun AddRepoDialog(onAdd: (name: String, baseUrl: String, indexUrl: String) -> Unit, onDismiss: () -> Unit) {
    var name by remember { mutableStateOf("") }
    var baseUrl by remember { mutableStateOf("") }
    var indexUrl by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add extension repository") },
        text = {
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
        },
        confirmButton = {
            Button(
                onClick = { onAdd(name.trim(), baseUrl.trim(), indexUrl.trim()) },
                enabled = baseUrl.isNotBlank(),
            ) { Text("Add") }
        },
        dismissButton = { TextButton(onClick = onDismiss) { Text("Cancel") } },
    )
}
