package com.folio.reader.ui.library

import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.components.FolioDialog
import com.folio.reader.model.Collection
import com.folio.reader.ui.theme.FolioTheme
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.launch

/**
 * Post-import shelf picker: after a batch of books lands in the library (and on
 * Main), the reader can file the whole batch into one or more collections in
 * one move — the book counterpart of the prompt the manga library shows when a
 * title is added to the library.
 *
 * Main arrives pre-checked because that is where the imports already are;
 * unchecking it and saving another set moves the batch off Main, exactly the
 * way the per-book and bulk pickers behave.
 */
@Composable
fun BookImportCollectionsDialog(
    bookCount: Int,
    collections: List<Collection>,
    onCreate: suspend (String) -> String?,
    onSave: suspend (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    LibraryAssignmentDialog(
        title = "Add to collections",
        choices = collections.map { it.id to it.name },
        initialSelected = collections.firstOrNull { it.id == Collection.MAIN_ID }
            ?.let { setOf(it.id) } ?: emptySet(),
        kind = "collection",
        description = if (bookCount == 1) "Where should the imported book go?"
            else "Where should the $bookCount imported books go?",
        onCreate = onCreate,
        onConfirm = onSave,
        onDismiss = onDismiss,
    )
}

/** A picker session owns its draft and closes only after the assignment succeeds. */
@Composable
internal fun LibraryAssignmentDialog(
    title: String,
    choices: List<Pair<String, String>>,
    initialSelected: Set<String>?,
    kind: String,
    onCreate: suspend (String) -> String?,
    onConfirm: suspend (Set<String>) -> Unit,
    onDismiss: () -> Unit,
    description: String? = null,
) {
    val scope = rememberCoroutineScope()
    var selected by remember { mutableStateOf(initialSelected ?: emptySet()) }
    var initialized by remember { mutableStateOf(initialSelected != null) }
    var newName by remember { mutableStateOf("") }
    var saving by remember { mutableStateOf(false) }
    var creating by remember { mutableStateOf(false) }
    var error by remember { mutableStateOf<String?>(null) }
    val busy = saving || creating

    // A delayed initial read may fill the draft once, but live shelf updates must not reset it.
    LaunchedEffect(initialSelected) {
        if (!initialized && initialSelected != null) {
            selected = initialSelected
            initialized = true
        }
    }

    FolioDialog(
        expanded = true,
        onDismissRequest = { if (!saving && !creating) onDismiss() },
        title = title,
    ) {
        Column(
            modifier = Modifier.padding(horizontal = 16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp),
        ) {
            description?.let {
                Text(
                    it,
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
            if (!initialized || choices.isEmpty()) {
                Text(
                    if (!initialized) "Loading current shelves..." else "No shelves yet. Create one below.",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
            LazyColumn(
                modifier = Modifier.heightIn(max = 300.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                items(choices, key = { it.first }) { (id, name) ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .clickable(enabled = initialized && !busy) {
                                selected = if (id in selected) selected - id else selected + id
                            }
                            .padding(vertical = 4.dp),
                        verticalAlignment = Alignment.CenterVertically,
                    ) {
                        Checkbox(
                            checked = id in selected,
                            onCheckedChange = { checked ->
                                selected = if (checked) selected + id else selected - id
                            },
                            enabled = initialized && !busy,
                        )
                        Spacer(Modifier.width(8.dp))
                        Text(
                            name,
                            style = MaterialTheme.typography.bodyMedium,
                            color = FolioTheme.colors.onSurface,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis,
                        )
                    }
                }
            }
            Row(verticalAlignment = Alignment.CenterVertically) {
                OutlinedTextField(
                    value = newName,
                    onValueChange = { newName = it },
                    label = { Text("New $kind") },
                    singleLine = true,
                    enabled = initialized && !busy,
                    modifier = Modifier.weight(1f),
                )
                IconButton(
                    onClick = {
                        if (!saving && !creating && newName.trim().isNotEmpty()) {
                            val name = newName.trim()
                            creating = true
                            error = null
                            scope.launch {
                                try {
                                    val id = requireNotNull(onCreate(name)) { "Could not create $kind." }
                                    selected = selected + id
                                    newName = ""
                                } catch (e: CancellationException) {
                                    throw e
                                } catch (e: Exception) {
                                    error = e.message ?: "Could not create $kind."
                                } finally {
                                    creating = false
                                }
                            }
                        }
                    },
                    enabled = initialized && !busy && newName.trim().isNotEmpty(),
                ) {
                    Icon(Icons.Filled.Add, contentDescription = "Create $kind")
                }
            }
            if (initialized && selected.isEmpty()) {
                Text(
                    "With none selected, the default shelf is used.",
                    style = MaterialTheme.typography.bodySmall,
                    color = FolioTheme.colors.onSurfaceVariant,
                )
            }
            error?.let {
                Text(it, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.error)
            }
        }
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss, enabled = !busy) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            TextButton(
                enabled = initialized && !busy,
                onClick = {
                    if (initialized && !saving && !creating) {
                        saving = true
                        error = null
                        scope.launch {
                            try {
                                onConfirm(selected)
                                onDismiss()
                            } catch (e: CancellationException) {
                                throw e
                            } catch (e: Exception) {
                                error = e.message ?: "Could not save shelves. Try again."
                            } finally {
                                saving = false
                            }
                        }
                    }
                },
            ) {
                Text(if (saving) "Saving..." else "Confirm")
            }
        }
    }
}
