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
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.atmosphere

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.foundation.border

/**
 * Legacy name, new material. Every existing call site keeps working, but the
 * rendering is now the §R1 panel material: a resting surface with a hairline rim
 * and a whisper of shadow, instead of the old white-sheen glass that made every
 * card in the app the same object.
 *
 * New code should reach for [folioPanel], [folioRaised], [folioSunken] or
 * [folioVeil] directly and pick the one that matches what the surface is *doing*.
 */
@Composable
fun Modifier.glassPanel(shape: Shape, accent: Color? = null): Modifier =
    this.folioPanel(shape, accent)

/**
 * Grouped content. The heading is an eyebrow — small, letterspaced, sitting on
 * the panel's own top edge — not a title competing with the screen heading. Use
 * [FolioSectionHead] outside a panel when a section deserves a real voice.
 */
@Composable
fun FolioSectionCard(
    title: String? = null,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            .folioPanel(com.folio.reader.ui.theme.FolioShapes.card, accent)
            .padding(com.folio.reader.ui.theme.FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(com.folio.reader.ui.theme.FolioTokens.space2)
    ) {
        if (title != null) {
            FolioEyebrow(
                text = title,
                accent = accent ?: com.folio.reader.ui.theme.FolioTheme.colors.onSurfaceVariant,
            )
        }
        content()
    }
}

/**
 * The hero tier: the one surface a screen is about. Now genuinely a different
 * *object* rather than a card with a tint —
 *
 *  - [FolioShapes.hero]'s asymmetric silhouette (34/20/34/8) so it is
 *    identifiable at 8px blur, which is Rule 13's own benchmark;
 *  - the raised material, so it floats while panels rest;
 *  - the accent bled into the top light catch, so a cover-tinted hero reports
 *    that cover's hue at its lit edge.
 *
 * [mesh] keeps the §13.4 drifting gradient mesh; [gradientAlpha] keeps the §13.9
 * collapse fade. At most one per screen.
 */
@Composable
fun FolioHeroCard(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    mesh: Boolean = false,
    gradientAlpha: Float = 0.30f,
    shape: Shape = com.folio.reader.ui.theme.FolioShapes.hero,
    content: @Composable androidx.compose.foundation.layout.BoxScope.() -> Unit
) {
    val tint = accent ?: com.folio.reader.ui.theme.FolioTheme.colors.accentProgress
    val meshModifier = if (mesh) {
        val colors = com.folio.reader.ui.theme.FolioTheme.colors
        Modifier.heroMesh(
            layers = listOf(tint, colors.accentDiscovery, colors.accentProgress),
            animate = com.folio.reader.ui.theme.rememberMotionEnabled()
        )
    } else Modifier
    Box(
        modifier = modifier
            .fillMaxWidth()
            .folioRaised(shape = shape, accent = tint)
            .background(
                brush = Brush.verticalGradient(
                    listOf(tint.copy(alpha = gradientAlpha), Color.Transparent)
                ),
                shape = shape
            )
            .then(meshModifier)
            .padding(com.folio.reader.ui.theme.FolioTokens.space3)
    ) {
        content()
    }
}

/**
 * §12.1 Rule 13 quiet tier: no fill, no border — just breathing room. For
 * lists, secondary stats and metadata that must not compete with cards.
 */
@Composable
fun FolioQuietRow(
    modifier: Modifier = Modifier,
    content: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit
) {
    Row(
        modifier = modifier
            .fillMaxWidth()
            .padding(vertical = com.folio.reader.ui.theme.FolioTokens.space2),
        verticalAlignment = Alignment.CenterVertically
    ) {
        content()
    }
}

/**
 * Filter/segment control. A **pill**, because in this design language a pill means
 * "tap me" — panels and figures are never pill-shaped, so shape alone now tells
 * the user what is interactive.
 *
 * Selection is carried by fill and by an accent rim, never by weight: a heavier
 * label widens the chip, reflows the row and nudges the grid below it.
 */
@Composable
fun FolioChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier
) {
    val shape = com.folio.reader.ui.theme.FolioShapes.pill
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val interaction = rememberFolioInteraction()
    Box(
        modifier = modifier
            .folioPressable(interaction, scaleTo = 0.94f)
            .clip(shape)
            .background(
                if (selected) colors.primary else atmos.sunkenFill.copy(alpha = 0.55f),
                shape
            )
            .border(
                1.dp,
                if (selected) colors.primary else atmos.hairline,
                shape
            )
            .clickable(interactionSource = interaction, indication = null, onClick = onClick)
            .padding(horizontal = 15.dp, vertical = 8.dp)
    ) {
        Text(
            text = label,
            style = MaterialTheme.typography.labelLarge,
            maxLines = 1,
            overflow = TextOverflow.Ellipsis,
            fontWeight = FontWeight.Medium,
            color = if (selected) colors.onPrimary else colors.onSurfaceVariant
        )
    }
}

/**
 * The screen's masthead. Editorial, not Material: the title carries the display
 * face at `headlineMedium`, the bar has **no fill of its own** so the page's field
 * runs behind it, and a hairline marks the boundary instead of a shadow.
 *
 * That single change removes the "grey band on top of every screen" that made the
 * old build read as a scaffold. [titleStyle] still lets the §13.9 collapsing Home
 * hero migrate its title in at a smaller size.
 *
 * Top-level destinations pass no [navigationIcon], so they get the Folio mark in
 * that slot: the four persistent screens (Folio, Home, Stats, More) then read as
 * one product masthead rather than four unrelated screen titles, while pushed
 * screens keep their back arrow exactly where it was.
 */
@Composable
fun FolioTopBar(
    title: String,
    modifier: Modifier = Modifier,
    titleStyle: androidx.compose.ui.text.TextStyle? = null,
    navigationIcon: (@Composable () -> Unit)? = null,
    actions: @Composable androidx.compose.foundation.layout.RowScope.() -> Unit = {},
) {
    val colors = FolioTheme.colors
    Column(modifier = modifier.fillMaxWidth()) {
        FolioStatusBarBand()
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .height(com.folio.reader.ui.theme.FolioTokens.barHeight)
                .padding(horizontal = 12.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            if (navigationIcon != null) {
                navigationIcon()
            } else {
                FolioMark()
            }
            Text(
                text = title,
                style = titleStyle ?: FolioTheme.typography.headlineMedium,
                color = colors.onSurface,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                modifier = Modifier
                    .weight(1f)
                    .padding(horizontal = 10.dp)
            )
            actions()
        }
        // The boundary the bar has always claimed but never drew: a hairline that
        // fades out toward the trailing edge, so the masthead is separated from the
        // page without a shadow or a fill.
        FolioRule()
    }
}

/**
 * The Folio mark: the wordmark's initial on a small tinted plate. Deliberately not
 * an icon — a letterform in the product's own type reads as identity, where a
 * generic book glyph reads as a category.
 */
@Composable
private fun FolioMark(modifier: Modifier = Modifier) {
    val colors = FolioTheme.colors
    Box(
        modifier = modifier
            .size(32.dp)
            .background(colors.primaryContainer, FolioShapes.inset),
        contentAlignment = Alignment.Center,
    ) {
        Text(
            text = "F",
            style = FolioTheme.typography.titleMedium,
            color = colors.onPrimaryContainer,
            fontWeight = FontWeight.Bold,
        )
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

/**
 * Progress as a hairline seam, not a widget. 3dp, squared caps at the leading
 * edge, drawn in the accent's own colour over a very low-alpha track — it reads as
 * an inlay in the surface rather than another control.
 */
@Composable
fun FolioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.colors.accentProgress
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .height(3.dp)
            .background(color.copy(alpha = 0.14f), RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(progress.coerceIn(0f, 1f))
                .height(3.dp)
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

/**
 * A statistic rendered as an editorial figure on an embedded well, not as another
 * elevated card. The old version was a Material `Card` with a bold
 * `headlineMedium` — visually identical to every other card on the screen, which
 * is exactly why a screen of eight of them read as a spreadsheet.
 */
@Composable
fun StatCard(
    title: String,
    value: String,
    subtitle: String? = null,
    color: Color = FolioTheme.colors.accentProgress,
    modifier: Modifier = Modifier
) {
    Box(
        modifier = modifier
            .fillMaxWidth()
            .folioSunken(FolioShapes.inset, accent = color)
            .padding(horizontal = 14.dp, vertical = 13.dp)
    ) {
        FolioFigure(
            value = value,
            label = title,
            caption = subtitle,
            accent = color,
            emphasis = FigureScale.Quiet,
        )
    }
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
