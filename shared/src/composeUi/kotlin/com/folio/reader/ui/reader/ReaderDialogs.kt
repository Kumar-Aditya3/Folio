package com.folio.reader.ui.reader

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.theme.FolioTheme

/**
 * Glass composer for a highlight's note. A dismissed (not cancelled) dialog
 * keeps its unsaved draft — the caller owns that state via [onDismiss] vs
 * [onCancel].
 */
@Composable
internal fun NoteComposerDialog(
    passage: String,
    noteContent: String,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Note") },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                if (passage.isNotBlank()) {
                    Text(
                        text = "“${passage.take(200)}”",
                        style = FolioTheme.typography.quote.copy(fontSize = 14.sp, lineHeight = 20.sp),
                        color = FolioTheme.colors.onSurfaceVariant,
                        maxLines = 4,
                        overflow = TextOverflow.Ellipsis
                    )
                }
                OutlinedTextField(
                    value = noteContent,
                    onValueChange = onNoteChange,
                    label = { Text("Your note") },
                    minLines = 3,
                    modifier = Modifier.fillMaxWidth()
                )
            }
        },
        confirmButton = {
            TextButton(
                onClick = onSave,
                enabled = noteContent.isNotBlank()
            ) { Text("Save") }
        },
        dismissButton = {
            TextButton(onClick = onCancel) { Text("Cancel") }
        }
    )
}
