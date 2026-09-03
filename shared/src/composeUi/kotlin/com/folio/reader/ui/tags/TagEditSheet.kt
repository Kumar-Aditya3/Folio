package com.folio.reader.ui.tags

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.grid.GridCells
import androidx.compose.foundation.lazy.grid.LazyVerticalGrid
import androidx.compose.foundation.lazy.grid.items
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.folio.reader.model.Book
import com.folio.reader.model.Highlight
import com.folio.reader.model.Tag
import com.folio.reader.ui.components.folioPanel
import com.folio.reader.ui.components.folioSunken
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme

internal val TagPresetColors = listOf(
    0xFFF44336.toInt(),
    0xFFFF9800.toInt(),
    0xFFFFEB3B.toInt(),
    0xFF4CAF50.toInt(),
    0xFF00BCD4.toInt(),
    0xFF2196F3.toInt(),
    0xFF3F51B5.toInt(),
    0xFF9C27B0.toInt(),
    0xFFE91E63.toInt(),
    0xFF795548.toInt(),
    0xFF607D8B.toInt(),
    0xFF000000.toInt()
)

@Composable
internal fun TagDetailView(
    detail: TagDetailItem,
    padding: PaddingValues,
    onHighlightClick: (Highlight, Book) -> Unit,
    onBookClick: (Book) -> Unit
) {
    LazyColumn(
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(16.dp),
        modifier = Modifier.padding(padding)
    ) {
        item {
            val tagColor = detail.tag.color?.let { Color(it) } ?: FolioTheme.colors.primary
            Box(
                modifier = Modifier
                    .fillMaxWidth()
                    // The tag's own colour tints a well: the tag is the subject of
                    // this screen, so its identity is the surface, not a badge.
                    .folioSunken(FolioShapes.card, accent = tagColor)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Text(
                        detail.tag.name,
                        style = FolioTheme.typography.headlineMedium,
                        color = tagColor
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        "${detail.books.size} book${if (detail.books.size != 1) "s" else ""} \u00B7 ${detail.highlights.size} highlight${if (detail.highlights.size != 1) "s" else ""}",
                        style = FolioTheme.typography.bodyMedium,
                        color = FolioTheme.colors.onSurfaceVariant
                    )
                }
            }
        }

        if (detail.books.isNotEmpty()) {
            item {
                Text(
                    "Books",
                    style = FolioTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(detail.books, key = { it.id }) { book ->
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .folioPanel(FolioShapes.inset)
                        .clickable { onBookClick(book) }
                ) {
                    Row(
                        modifier = Modifier.padding(12.dp),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Box(
                            modifier = Modifier
                                .width(40.dp)
                                .height(60.dp)
                                .background(FolioTheme.colors.surfaceContainerHighest, RoundedCornerShape(4.dp)),
                            contentAlignment = Alignment.Center
                        ) {
                            Text(
                                book.title.take(2),
                                color = FolioTheme.colors.onSurfaceVariant,
                                style = FolioTheme.typography.labelLarge,
                                fontWeight = FontWeight.Bold
                            )
                        }
                        Spacer(modifier = Modifier.width(12.dp))
                        Column(modifier = Modifier.weight(1f)) {
                            Text(
                                book.title,
                                style = FolioTheme.typography.bodyLarge,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                            Text(
                                book.displayAuthor,
                                style = FolioTheme.typography.bodySmall,
                                color = FolioTheme.colors.onSurfaceVariant,
                                maxLines = 1,
                                overflow = TextOverflow.Ellipsis
                            )
                        }
                    }
                }
            }
        }

        if (detail.highlights.isNotEmpty()) {
            item {
                Text(
                    "Highlights",
                    style = FolioTheme.typography.titleMedium,
                    fontWeight = FontWeight.SemiBold
                )
            }
            items(detail.highlights, key = { it.highlight.id }) { hw ->
                val hlColor = hw.highlight.effectiveColor.let { Color(it) }
                Box(
                    modifier = Modifier
                        .fillMaxWidth()
                        .folioPanel(FolioShapes.inset, accent = hlColor)
                        .clickable { onHighlightClick(hw.highlight, hw.book) }
                ) {
                    Column(modifier = Modifier.padding(12.dp)) {
                        Box(
                            modifier = Modifier
                                .fillMaxWidth()
                                .height(3.dp)
                                .background(hlColor, RoundedCornerShape(2.dp))
                        )
                        Spacer(modifier = Modifier.height(8.dp))
                        Text(
                            "\u201C${hw.highlight.selectedText}\u201D",
                            style = FolioTheme.typography.bodyMedium,
                            maxLines = 3,
                            overflow = TextOverflow.Ellipsis
                        )
                        Spacer(modifier = Modifier.height(6.dp))
                        Text(
                            hw.book.title,
                            style = FolioTheme.typography.bodySmall,
                            color = FolioTheme.colors.onSurfaceVariant,
                            maxLines = 1,
                            overflow = TextOverflow.Ellipsis
                        )
                    }
                }
            }
        }
    }
}

@Composable
internal fun TagEditDialog(
    title: String,
    initialName: String,
    initialColor: Int,
    onConfirm: (String, Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var name by remember { mutableStateOf(initialName) }
    var selectedColor by remember { mutableStateOf<Int?>(initialColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(title, style = FolioTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                OutlinedTextField(
                    value = name,
                    onValueChange = { name = it },
                    modifier = Modifier.fillMaxWidth(),
                    label = { Text("Tag name") },
                    singleLine = true
                )

                Text(
                    "Color",
                    style = FolioTheme.typography.labelLarge,
                    fontWeight = FontWeight.SemiBold
                )

                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ColorSwatch(
                            color = null,
                            selected = selectedColor == null,
                            onClick = { selectedColor = null }
                        )
                    }
                    items(TagPresetColors) { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(
                onClick = { onConfirm(name, selectedColor) },
                enabled = name.isNotBlank()
            ) {
                Text("Save")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
internal fun ColorPickerDialog(
    currentColor: Int?,
    onColorSelected: (Int?) -> Unit,
    onDismiss: () -> Unit
) {
    var selectedColor by remember { mutableStateOf(currentColor) }

    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text("Choose Color", style = FolioTheme.typography.titleLarge) },
        text = {
            Column(verticalArrangement = Arrangement.spacedBy(16.dp)) {
                LazyVerticalGrid(
                    columns = GridCells.Fixed(6),
                    horizontalArrangement = Arrangement.spacedBy(8.dp),
                    verticalArrangement = Arrangement.spacedBy(8.dp)
                ) {
                    item {
                        ColorSwatch(
                            color = null,
                            selected = selectedColor == null,
                            onClick = { selectedColor = null }
                        )
                    }
                    items(TagPresetColors) { color ->
                        ColorSwatch(
                            color = color,
                            selected = selectedColor == color,
                            onClick = { selectedColor = color }
                        )
                    }
                }
            }
        },
        confirmButton = {
            Button(onClick = { onColorSelected(selectedColor) }) {
                Text("Apply")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}

@Composable
private fun ColorSwatch(
    color: Int?,
    selected: Boolean,
    onClick: () -> Unit
) {
    Box(
        modifier = Modifier
            .size(40.dp)
            .background(
                color = color?.let { Color(it) } ?: FolioTheme.colors.surfaceContainerHighest,
                shape = CircleShape
            )
            .then(
                if (selected) Modifier.background(
                    FolioTheme.colors.primaryContainer,
                    CircleShape
                ) else Modifier
            )
            .clickable { onClick() },
        contentAlignment = Alignment.Center
    ) {
        if (color == null) {
            Text("\u00D8", style = FolioTheme.typography.labelLarge)
        } else if (selected) {
            Icon(
                Icons.Filled.Check,
                contentDescription = null,
                tint = FolioTheme.colors.onPrimaryContainer
            )
        }
    }
}

@Composable
internal fun DeleteTagDialog(
    tag: Tag,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = {
            Text("Delete \u201C${tag.name}\u201D?", style = FolioTheme.typography.titleLarge)
        },
        text = {
            Text("This will remove the tag from all books and highlights. This cannot be undone.")
        },
        confirmButton = {
            Button(onClick = onConfirm) {
                Text("Delete")
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text("Cancel")
            }
        }
    )
}
