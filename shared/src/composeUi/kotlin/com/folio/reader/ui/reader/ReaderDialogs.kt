@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.reader

import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.FolioDialog
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
    // §16 issue 3: the app's own in-window pane, not Material's `AlertDialog`. An `AlertDialog`
    // is a separate window, so it paints `colorScheme.surfaceContainerHigh` with no backdrop to
    // sample — an opaque slab laid over the page the note is *about*, which is the worst possible
    // surface for it. See FolioDialog / FolioOverlayHost. The pane is glass over the page and the
    // host supplies the scrim, back handling and the keyboard inset.
    FolioDialog(
        expanded = true,
        onDismissRequest = onDismiss,
        title = "Note",
    ) {
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
        // The confirm/dismiss slots an `AlertDialog` supplied, as the pane's own last row: the
        // cancel path stays distinct from the dismiss path, so a scrim tap or a back gesture
        // still keeps the unsaved draft and only "Cancel" discards it.
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = 16.dp),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onCancel) { Text("Cancel") }
            Spacer(Modifier.width(8.dp))
            TextButton(
                onClick = onSave,
                enabled = noteContent.isNotBlank()
            ) { Text("Save") }
        }
    }
}
