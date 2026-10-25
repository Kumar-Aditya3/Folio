package com.folio.reader.ui.reader

import androidx.compose.animation.*
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Check
import androidx.compose.material.icons.filled.KeyboardArrowDown
import androidx.compose.material3.Icon
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.rotate
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.semantics.stateDescription
import androidx.compose.ui.text.font.FontFamily
import com.folio.reader.settings.CustomFont
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.IntSize
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.components.FolioBackHandler
import com.folio.reader.ui.components.folioSettingsChoice
import com.folio.reader.ui.components.rememberLegibleAccent
import com.folio.reader.ui.components.systemFontFamily
import com.folio.reader.ui.theme.*

/** Font choices stay in the reader sheet, sharing its material and scroll context. */
@Composable
internal fun ReaderTypefacePicker(
    selectedFont: String,
    fonts: List<String>,
    onSelect: (String) -> Unit,
    displayName: (String) -> String = { it },
    customFonts: List<CustomFont> = emptyList(),
) {
    fun previewFamily(key: String): FontFamily? {
        val custom = customFonts.firstOrNull { it.name == key }
        return custom?.let { UiFonts.fromFile(it.fileName, it.weight) }
            ?: systemFontFamily(custom?.familyName ?: displayName(key))
    }
    var expanded by remember { mutableStateOf(false) }
    val colors = FolioTheme.colors
    val accent = rememberLegibleAccent(colors.primary)
    val motion = rememberMotionEnabled()
    val fade = rememberMotionAnimation<Float>(MotionKey.OverlayEnter)
    val size = rememberMotionAnimation<IntSize>(MotionKey.SheetEnter)
    val target = if (expanded) 180f else 0f
    val turn = if (motion) animateFloatAsState(target, fade, label = "Typeface disclosure").value else target
    val choices = fonts.distinct().ifEmpty { platformBaseReaderFonts() }
    FolioBackHandler(enabled = expanded) { expanded = false }
    Column(Modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(8.dp)) {
        Row(Modifier.fillMaxWidth()
            .semantics { stateDescription = if (expanded) "Expanded" else "Collapsed" }
            .clickable(role = Role.Button) { expanded = !expanded }
            .folioSettingsChoice(false, FolioShapes.inset)
            .padding(horizontal = 14.dp, vertical = 12.dp),
            verticalAlignment = Alignment.CenterVertically) {
            Text(displayName(selectedFont), modifier = Modifier.weight(1f),
                style = FolioTheme.typography.bodyMedium, color = colors.onSurface,
                maxLines = 1, overflow = TextOverflow.Ellipsis,
                fontFamily = remember(selectedFont, customFonts) { previewFamily(selectedFont) })
            Icon(Icons.Filled.KeyboardArrowDown, null, Modifier.rotate(turn), tint = colors.onSurfaceVariant)
        }
        AnimatedVisibility(expanded,
            enter = if (motion) expandVertically(size, expandFrom = Alignment.Top) + fadeIn(fade) else EnterTransition.None,
            exit = if (motion) shrinkVertically(size, shrinkTowards = Alignment.Top) + fadeOut(fade) else ExitTransition.None) {
            Column(Modifier.fillMaxWidth().heightIn(max = 240.dp)
                .folioSettingsChoice(false, FolioShapes.inset)
                .verticalScroll(rememberScrollState()).selectableGroup().padding(4.dp),
                verticalArrangement = Arrangement.spacedBy(4.dp)) {
                choices.forEach { font ->
                    val selected = font == selectedFont
                    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
                        .selectable(selected = selected, role = Role.RadioButton, onClick = {
                            onSelect(font)
                            expanded = false
                        })
                        .folioSettingsChoice(selected, FolioShapes.chip)
                        .padding(horizontal = 12.dp, vertical = 8.dp),
                        horizontalArrangement = Arrangement.spacedBy(8.dp),
                        verticalAlignment = Alignment.CenterVertically) {
                        Text(displayName(font), Modifier.weight(1f),
                            style = FolioTheme.typography.bodyMedium,
                            fontFamily = remember(font, customFonts) { previewFamily(font) },
                            fontSize = 16.sp, lineHeight = 22.sp,
                            color = if (selected) accent else colors.onSurface,
                            maxLines = 1, overflow = TextOverflow.Ellipsis)
                        if (selected) Icon(Icons.Filled.Check, null, Modifier.size(16.dp), tint = accent)
                        else Spacer(Modifier.size(16.dp))
                    }
                }
            }
        }
    }
}
