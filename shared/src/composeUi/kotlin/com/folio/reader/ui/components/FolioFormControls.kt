package com.folio.reader.ui.components

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.interaction.collectIsFocusedAsState
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.text.BasicTextField
import androidx.compose.material3.TextButton
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LocalContentColor
import androidx.compose.material3.ProvideTextStyle
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.elements

/** The same quiet, etched action used by Folio's settings choices. */
@Composable
fun FolioActionButton(onClick: () -> Unit, modifier: Modifier = Modifier, enabled: Boolean = true,
    content: @Composable RowScope.() -> Unit) {
    val elements = FolioTheme.elements
    TextButton(onClick, modifier.heightIn(min = 48.dp), enabled = enabled,
        shape = FolioShapes.pill,
        border = BorderStroke(.75.dp, elements.border.copy(alpha = if (enabled) 1f else .4f)),
        colors = ButtonDefaults.textButtonColors(contentColor = elements.onSelection,
            containerColor = elements.selection),
        contentPadding = PaddingValues(horizontal = 16.dp, vertical = 8.dp)) {
        ProvideTextStyle(FolioTheme.typography.labelLarge) { content() }
    }
}

/** Labelled inset field, without an unrelated outlined Material container. */
@Composable
fun FolioInputField(value: String, onValueChange: (String) -> Unit, modifier: Modifier = Modifier,
    label: (@Composable () -> Unit)? = null, placeholder: (@Composable () -> Unit)? = null,
    singleLine: Boolean = true, enabled: Boolean = true) {
    val elements = FolioTheme.elements
    val interaction = remember { MutableInteractionSource() }
    val focused by interaction.collectIsFocusedAsState()
    val edge = if (focused && enabled) legibleOn(elements.action, elements.inset, elements.ink, 3.0)
        else elements.border
    Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(6.dp)) {
        androidx.compose.runtime.CompositionLocalProvider(LocalContentColor provides FolioTheme.colors.onSurfaceVariant) {
            ProvideTextStyle(FolioTheme.typography.labelMedium) { label?.invoke() }
        }
        BasicTextField(value, onValueChange, enabled = enabled, singleLine = singleLine,
            interactionSource = interaction,
            textStyle = FolioTheme.typography.bodyMedium.copy(color = elements.ink),
            cursorBrush = SolidColor(elements.actionInk),
            modifier = Modifier.fillMaxWidth().background(elements.inset, FolioShapes.inset)
                .border(if (focused) 1.5.dp else .75.dp, edge, FolioShapes.inset)
                .heightIn(min = 48.dp).padding(horizontal = 14.dp, vertical = 12.dp),
            decorationBox = { inner -> Box {
                if (value.isEmpty()) androidx.compose.runtime.CompositionLocalProvider(
                    LocalContentColor provides FolioTheme.colors.onSurfaceVariant) { placeholder?.invoke() }
                inner()
            } })
    }
}
