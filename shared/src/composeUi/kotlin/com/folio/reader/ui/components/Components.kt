package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.statusBarsPadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.statusBars
import androidx.compose.foundation.layout.windowInsetsTopHeight
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material.icons.filled.Search
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material3.AlertDialog
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.DpOffset
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border

/**
 * Frosted-glass surface that follows the active theme: dark frosted glass on dark
 * themes, light frosted glass on light ones, so panels never read as a foreign
 * smudge over the page or app background.
 *
 * The fill stays near-opaque on purpose. A panel cannot blur what is behind it on
 * Android — the page is a separate native surface, so there is nothing to sample —
 * and a see-through fill let book text bleed through at full contrast, which read as
 * labels dissolving into the theme. Glass is carried by the rim, the sheen and the
 * elevation instead.
 */
@Composable
fun Modifier.glassPanel(shape: Shape): Modifier {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val luminance = colors.background.red * 0.2126f + colors.background.green * 0.7152f + colors.background.blue * 0.0722f
    val dark = luminance < 0.45f
    val fill = if (dark) colors.surface.copy(alpha = 0.86f) else colors.surface.copy(alpha = 0.89f)
    return this
        .shadow(14.dp, shape, ambientColor = Color.Black, spotColor = Color.Black)
        .background(fill, shape)
        .background(
            brush = Brush.verticalGradient(
                colors = if (dark) listOf(
                    Color.White.copy(alpha = 0.14f),
                    Color.White.copy(alpha = 0.04f),
                    Color.White.copy(alpha = 0.08f)
                ) else listOf(
                    Color.White.copy(alpha = 0.26f),
                    Color.White.copy(alpha = 0.08f),
                    Color.White.copy(alpha = 0.17f)
                )
            ),
            shape = shape
        )
        .border(1.dp, if (dark) Color.White.copy(alpha = 0.26f) else Color.Black.copy(alpha = 0.14f), shape)
        .clip(shape)
}

/** Glass card with an optional section header — the shared container for grouped content. */
@Composable
fun FolioSectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .glassPanel(RoundedCornerShape(com.folio.reader.ui.theme.FolioTokens.radiusCard))
            .padding(com.folio.reader.ui.theme.FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(com.folio.reader.ui.theme.FolioTokens.space2)
    ) {
        if (title != null) {
            Text(
                text = title,
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = MaterialTheme.colorScheme.primary
            )
        }
        content()
    }
}

/** Pill chip used for filters and segmented controls; glassy, theme-cohesive. */
@Composable
fun FolioChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val shape = RoundedCornerShape(com.folio.reader.ui.theme.FolioTokens.radiusChip)
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    Box(
        modifier = modifier
            .clip(shape)
            .background(
                if (selected) colors.primary.copy(alpha = 0.90f) else colors.surface.copy(alpha = 0.55f),
                shape
            )
            .border(
                1.dp,
                if (selected) colors.primary else colors.outline.copy(alpha = 0.45f),
                shape
            )
            .clickable(onClick = onClick)
            .padding(horizontal = 14.dp, vertical = 7.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            // Weight must not change with selection: a heavier weight widens the
            // chip, reflows the row and nudges the grid below it downward.
            fontWeight = FontWeight.Medium,
            color = if (selected) colors.onPrimary else colors.onSurface
        )
    }
}

/** Glass top bar shared by all screens: optional back button, title, actions. */
@Composable
fun FolioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        FolioStatusBarBand()
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .background(colors.surface.copy(alpha = 0.85f))
        ) {
            Row(
                modifier = Modifier
                    .fillMaxWidth()
                    .height(com.folio.reader.ui.theme.FolioTokens.barHeight)
                    .padding(horizontal = 8.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
            if (navigationIcon != null) navigationIcon()
            Text(
                text = title,
                style = MaterialTheme.typography.titleLarge,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 8.dp)
            )
            actions()
            }
        }
    }
}

/**
 * Darker themed band behind the OS status bar on non-reader screens, so the
 * notification icons always sit on the theme's own ink instead of blending into
 * the background. Zero-height on desktop, where there is no OS bar.
 */
@Composable
fun FolioStatusBarBand(modifier: Modifier = Modifier) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(com.folio.reader.ui.theme.FolioTheme.colors.statusBar)
    )
}

/** Thin rounded progress bar in the theme accent. */
@Composable
fun FolioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = MaterialTheme.colorScheme.primary
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(4.dp)
            .background(color.copy(alpha = 0.18f), RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(4.dp)
                .background(color, RoundedCornerShape(2.dp))
        )
    }
}

@Composable
fun DropdownMenuButton(
    label: String,
    selected: String,
    options: List<String>,
    onChange: (String) -> Unit,
    modifier: Modifier = Modifier,
    optionContent: @Composable (String) -> Unit = { option ->
        Text(
            text = option,
            fontWeight = if (option == selected) FontWeight.SemiBold else FontWeight.Normal,
            color = if (option == selected) MaterialTheme.colorScheme.primary else MaterialTheme.colorScheme.onSurface
        )
    },
    selectedContent: @Composable () -> Unit = {
        Text(
            text = selected,
            style = MaterialTheme.typography.bodyMedium,
            fontWeight = FontWeight.SemiBold,
            color = MaterialTheme.colorScheme.onSurface,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis
        )
    }
) {
    var expanded by remember { mutableStateOf(false) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .clickable { expanded = !expanded }
                .glassPanel(RoundedCornerShape(12.dp))
                .padding(horizontal = 16.dp, vertical = 14.dp),
            horizontalArrangement = Arrangement.spacedBy(12.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Spacer(modifier = Modifier.weight(1f))
            selectedContent()
            Icon(Icons.Filled.KeyboardArrowDown, contentDescription = null, tint = MaterialTheme.colorScheme.onSurfaceVariant)
        }

        DropdownMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            offset = DpOffset(maxWidth - 240.dp, 0.dp),
            containerColor = com.folio.reader.ui.theme.FolioTheme.colors.surface.copy(alpha = 0.97f),
            modifier = Modifier
                .width(240.dp)
                .glassPanel(RoundedCornerShape(12.dp))
                .border(1.dp, Color.White.copy(alpha = 0.18f), RoundedCornerShape(12.dp))
        ) {
            options.forEach { option ->
                val isSelected = option == selected
                DropdownMenuItem(
                    text = {
                        optionContent(option)
                    },
                    onClick = {
                        onChange(option)
                        expanded = false
                    },
                    leadingIcon = {
                        if (isSelected) {
                            Icon(
                                Icons.Filled.Check,
                                contentDescription = "Selected",
                                tint = MaterialTheme.colorScheme.primary
                            )
                        } else {
                            Spacer(modifier = Modifier.size(24.dp))
                        }
                    }
                )
            }
        }
    }
}

@Composable
fun FolioSearchBar(
    query: String,
    onQueryChange: (String) -> Unit,
    onClear: () -> Unit,
    onSearch: (String) -> Unit,
    modifier: Modifier = Modifier
) {
    TextField(
        value = query,
        onValueChange = onQueryChange,
        modifier = modifier
            .fillMaxWidth()
            .padding(16.dp),
        placeholder = { Text("Search...") },
        leadingIcon = { Icon(Icons.Filled.Search, contentDescription = "Search") },
        trailingIcon = {
            if (query.isNotBlank()) {
                Icon(
                    Icons.Filled.Close,
                    contentDescription = "Clear",
                    modifier = Modifier.clickable {
                        onClear()
                        onQueryChange("")
                    }
                )
            }
        },
        singleLine = true,
        keyboardOptions = KeyboardOptions(
            keyboardType = KeyboardType.Text,
            imeAction = ImeAction.Search
        ),
        keyboardActions = KeyboardActions(onSearch = { onSearch(query) })
    )
}

@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String? = null,
    color: Color = MaterialTheme.colorScheme.primary,
    modifier: Modifier = Modifier
) {
    Card(
        modifier = modifier.fillMaxWidth(),
        colors = CardDefaults.cardColors(
            containerColor = MaterialTheme.colorScheme.surface,
            contentColor = MaterialTheme.colorScheme.onSurface
        ),
        shape = RoundedCornerShape(12.dp)
    ) {
        Column(
            modifier = Modifier.padding(16.dp),
            verticalArrangement = Arrangement.spacedBy(8.dp)
        ) {
            Text(
                text = title,
                style = MaterialTheme.typography.labelMedium,
                color = MaterialTheme.colorScheme.onSurfaceVariant
            )
            Text(
                text = value,
                style = MaterialTheme.typography.headlineMedium,
                fontWeight = FontWeight.Bold,
                color = color
            )
            if (subtitle != null) {
                Text(
                    text = subtitle,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
        }
    }
}

@Composable
fun HeatmapCell(
    intensity: Int,
    size: Dp = 12.dp,
    onClick: (() -> Unit)? = null
) {
    val base = MaterialTheme.colorScheme.surfaceVariant
    val colors = listOf(
        base,
        Color(0xFF4DD0E1),
        Color(0xFF00BCD4),
        Color(0xFF0097A7),
        Color(0xFF006064)
    )

    Box(
        modifier = Modifier
            .size(size)
            .then(
                if (onClick != null) {
                    Modifier.pointerInput(intensity) {
                        detectTapGestures(onTap = { onClick() })
                    }
                } else {
                    Modifier
                }
            )
            .background(colors[intensity.coerceIn(0, 4)], RoundedCornerShape(2.dp))
    )
}

@Composable
fun ConfirmDialog(
    title: String,
    message: String,
    confirmText: String = "OK",
    dismissText: String = "Cancel",
    destructive: Boolean = false,
    onConfirm: () -> Unit,
    onDismiss: () -> Unit
) {
    AlertDialog(
        onDismissRequest = onDismiss,
        title = { Text(text = title, style = MaterialTheme.typography.titleLarge) },
        text = { Text(text = message) },
        confirmButton = {
            Button(
                onClick = { onConfirm(); onDismiss() },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (destructive) com.folio.reader.ui.theme.FolioTheme.colors.error
                    else com.folio.reader.ui.theme.FolioTheme.colors.primary,
                    contentColor = if (destructive) com.folio.reader.ui.theme.FolioTheme.colors.onError
                    else com.folio.reader.ui.theme.FolioTheme.colors.onPrimary
                )
            ) {
                Text(confirmText)
            }
        },
        dismissButton = {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
        }
    )
}

/** In-progress statuses announce ongoing work ("Importing…", trailing ellipsis). */
fun isStatusInProgress(message: String): Boolean =
    message.startsWith("Importing") || message.endsWith("...") || message.endsWith("…")

/** Failures persist until replaced; anything containing "fail" reads as an error. */
fun isStatusError(message: String): Boolean =
    message.contains("fail", ignoreCase = true)

/** Only plain success messages auto-clear; progress and errors stay until replaced. */
fun isTransientStatus(message: String): Boolean =
    message.isNotBlank() && !isStatusInProgress(message) && !isStatusError(message)

/**
 * Bottom toast for app-wide transient messages (imports, exports, backups). The kind
 * is derived from the message itself so every entry point gets the same treatment:
 * a spinner while working, a warning glyph on the error container for failures, and
 * a check on the inverse surface for successes.
 */
@Composable
fun FolioStatusBanner(message: String, modifier: Modifier = Modifier) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val inProgress = isStatusInProgress(message)
    val isError = isStatusError(message)
    val container = if (isError) colors.errorContainer else colors.inverseSurface
    val content = if (isError) colors.onErrorContainer else colors.inverseOnSurface
    val shape = RoundedCornerShape(com.folio.reader.ui.theme.FolioTokens.radiusControl)

    androidx.compose.animation.AnimatedVisibility(
        visible = message.isNotBlank(),
        enter = androidx.compose.animation.fadeIn(androidx.compose.animation.core.tween(180)) +
            androidx.compose.animation.slideInVertically(androidx.compose.animation.core.tween(220)) { it / 2 },
        exit = androidx.compose.animation.fadeOut(androidx.compose.animation.core.tween(160)) +
            androidx.compose.animation.slideOutVertically(androidx.compose.animation.core.tween(200)) { it / 2 },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .shadow(10.dp, shape)
                .background(container, shape)
                .padding(horizontal = 16.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(10.dp)
        ) {
            when {
                inProgress -> CircularProgressIndicator(
                    modifier = Modifier.size(16.dp),
                    strokeWidth = 2.dp,
                    color = content
                )
                isError -> Icon(
                    Icons.Filled.Warning,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp)
                )
                else -> Icon(
                    Icons.Filled.Check,
                    contentDescription = null,
                    tint = content,
                    modifier = Modifier.size(16.dp)
                )
            }
            Text(
                text = message,
                color = content,
                style = com.folio.reader.ui.theme.FolioTheme.typography.labelLarge,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}
