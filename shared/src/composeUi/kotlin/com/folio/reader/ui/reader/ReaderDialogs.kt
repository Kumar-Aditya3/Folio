@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.ExperimentalSharedTransitionApi
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
import com.folio.reader.ui.components.sharedTextOrNoop
import com.folio.reader.ui.theme.FolioTheme

/**
 * Glass composer for a highlight's note. A dismissed (not cancelled) dialog
 * keeps its unsaved draft — the caller owns that state via [onDismiss] vs
 * [onCancel].
 *
 * @param morphKey §17 shared-element key published by the annotations row this
 *        composer was opened from, so the passage appears to lift out of that
 *        row. Null when there is no row to pair with. The modifier is inert
 *        wherever no shared transition scope is present (desktop, reduce-motion),
 *        so passing it is always safe.
 */
@Composable
internal fun NoteComposerDialog(
    passage: String,
    noteContent: String,
    onNoteChange: (String) -> Unit,
    onSave: () -> Unit,
    onDismiss: () -> Unit,
    onCancel: () -> Unit,
    morphKey: Any? = null,
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
                        overflow = TextOverflow.Ellipsis,
                        modifier = if (morphKey != null) {
                            Modifier.sharedTextOrNoop(morphKey)
                        } else Modifier,
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
