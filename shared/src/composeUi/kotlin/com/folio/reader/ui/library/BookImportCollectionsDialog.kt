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
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Checkbox
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Collection
import com.folio.reader.ui.theme.FolioTheme
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
    onSave: (Set<String>) -> Unit,
    onDismiss: () -> Unit
) {
    val scope = rememberCoroutineScope()
    var selected by remember(collections) {
        mutableStateOf(
            collections.firstOrNull { it.id == Collection.MAIN_ID }
                ?.let { setOf(it.id) }
                ?: emptySet()
        )
    }
    var newName by remember { mutableStateOf("") }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Add to collections") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                Text(
                    if (bookCount == 1) "Where should the imported book go?"
                    else "Where should the $bookCount imported books go?",
                    style = MaterialTheme.typography.bodyMedium,
                    color = FolioTheme.colors.onSurfaceVariant
                )
                LazyColumn(
                    modifier = Modifier.heightIn(max = 300.dp),
                    verticalArrangement = Arrangement.spacedBy(4.dp)
                ) {
                    items(collections, key = { it.id }) { collection ->
                        Row(
                            modifier = Modifier
                                .fillMaxWidth()
                                .clickable {
                                    selected =
                                        if (collection.id in selected) selected - collection.id
                                        else selected + collection.id
                                }
                                .padding(vertical = 4.dp),
                            verticalAlignment = Alignment.CenterVertically
                        ) {
                            Checkbox(
                                checked = collection.id in selected,
                                onCheckedChange = { checked ->
                                    selected =
                                        if (checked) selected + collection.id
                                        else selected - collection.id
                                }
                            )
                            Spacer(Modifier.width(8.dp))
                            Text(
                                collection.name,
                                style = MaterialTheme.typography.bodyMedium,
                                color = FolioTheme.colors.onSurface,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
                Row(verticalAlignment = Alignment.CenterVertically) {
                    OutlinedTextField(
                        value = newName,
                        onValueChange = { newName = it },
                        label = { Text("New collection") },
                        singleLine = true,
                        modifier = Modifier.weight(1f)
                    )
                    IconButton(
                        onClick = {
                            val name = newName.trim()
                            if (name.isNotEmpty()) {
                                scope.launch {
                                    onCreate(name)?.let { selected = selected + it }
                                }
                                newName = ""
                            }
                        },
                        enabled = newName.trim().isNotEmpty()
                    ) {
                        Icon(Icons.Filled.Add, contentDescription = "Create collection")
                    }
                }
            }
        },
        confirmButton = {
            TextButton(onClick = { onSave(selected) }) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) { Text("Skip") }
        }
    )
}
