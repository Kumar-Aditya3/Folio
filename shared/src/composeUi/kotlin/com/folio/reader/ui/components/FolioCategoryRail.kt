package com.folio.reader.ui.components

import androidx.compose.foundation.gestures.detectDragGesturesAfterLongPress
import androidx.compose.foundation.gestures.scrollBy
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.items
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalLayoutDirection
import androidx.compose.ui.semantics.CustomAccessibilityAction
import androidx.compose.ui.semantics.customActions
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.LayoutDirection
import androidx.compose.ui.zIndex
import com.folio.reader.ui.theme.FolioHaptic
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberFolioHaptics
import kotlin.math.abs

data class FolioCategoryChip(val id: String, val name: String, val canDelete: Boolean = true)

internal fun <T> moveCategory(items: List<T>, from: Int, to: Int): List<T> =
    if (from !in items.indices || to !in items.indices || from == to) items
    else items.toMutableList().apply { add(to, removeAt(from)) }

/** Shared category interaction for books, manga and documents. */
@Composable
fun FolioCategoryRail(
    categories: List<FolioCategoryChip>,
    selectedId: String?,
    onSelect: (String) -> Unit,
    onReorder: (List<String>) -> Unit,
    onRename: (String, String) -> Unit,
    onDelete: (String) -> Unit,
    onEdit: () -> Unit,
    leading: (@Composable () -> Unit)? = null,
    trailing: (@Composable () -> Unit)? = null,
    onDeleteRequest: ((String) -> Unit)? = null,
) {
    val list = rememberLazyListState()
    var ordered by remember { mutableStateOf(categories) }
    val latestCategories by rememberUpdatedState(categories)
    val reorder by rememberUpdatedState(onReorder)
    var dragging by remember { mutableStateOf<String?>(null) }
    var dragCenter by remember { mutableFloatStateOf(0f) }
    var dragTravel by remember { mutableFloatStateOf(0f) }
    var dragMoved by remember { mutableStateOf(false) }
    var menuId by remember { mutableStateOf<String?>(null) }
    var renameId by remember { mutableStateOf<String?>(null) }
    var deleteId by remember { mutableStateOf<String?>(null) }
    var name by remember { mutableStateOf("") }
    val anchors = remember { mutableStateMapOf<String, Rect>() }
    val haptics = rememberFolioHaptics()
    val density = LocalDensity.current.density
    val layoutSign = if (LocalLayoutDirection.current == LayoutDirection.Ltr) 1 else -1
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled() && !com.folio.reader.ui.theme.LocalMotion.current.reduced

    LaunchedEffect(categories) { if (dragging == null) ordered = categories }

    fun moveOverNeighbor() {
        val id = dragging ?: return
        val from = ordered.indexOfFirst { it.id == id }
        val current = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "category:$id" } ?: return
        val center = current.offset + current.size / 2f
        val candidate = list.layoutInfo.visibleItemsInfo.firstOrNull { item ->
            val candidateIndex = ordered.indexOfFirst { item.key == "category:${it.id}" }
            candidateIndex >= 0 && candidateIndex != from &&
                if (dragCenter > center) candidateIndex > from && dragCenter >= item.offset + item.size / 2f
                else candidateIndex < from && dragCenter <= item.offset + item.size / 2f
        } ?: return
        val to = ordered.indexOfFirst { candidate.key == "category:${it.id}" }
        ordered = moveCategory(ordered, from, to)
        dragMoved = true
        haptics.play(FolioHaptic.ScrubTick)
    }

    // Scroll the rail under the held chip so categories outside the viewport are reachable.
    LaunchedEffect(dragging) {
        if (dragging == null) return@LaunchedEffect
        var lastFrame = withFrameNanos { it }
        while (dragging != null) {
            val frame = withFrameNanos { it }
            val seconds = ((frame - lastFrame) / 1_000_000_000f).coerceAtMost(.05f)
            lastFrame = frame
            val start = list.layoutInfo.viewportStartOffset
            val end = list.layoutInfo.viewportEndOffset
            val edge = 48f * density
            val rate = when {
                dragCenter < start + edge -> -((start + edge - dragCenter) / edge).coerceIn(0f, 1f)
                dragCenter > end - edge -> ((dragCenter - end + edge) / edge).coerceIn(0f, 1f)
                else -> 0f
            }
            if (rate != 0f) list.scrollBy(rate * 500f * density * seconds)
            moveOverNeighbor()
        }
    }

    fun finishDrag(cancelled: Boolean) {
        val id = dragging ?: return
        if (cancelled) ordered = latestCategories
        else if (dragMoved) reorder(ordered.map { it.id })
        else if (abs(dragTravel) < 12f * density) menuId = id
        dragging = null
    }

    fun moveFromMenu(id: String, step: Int) {
        val from = ordered.indexOfFirst { it.id == id }
        ordered = moveCategory(ordered, from, from + step)
        reorder(ordered.map { it.id })
        menuId = null
    }

    LazyRow(
        state = list,
        modifier = Modifier.fillMaxWidth().pointerInput(layoutSign) {
            detectDragGesturesAfterLongPress(
                onDragStart = { position ->
                    val x = (if (layoutSign == 1) position.x else size.width - position.x) + list.layoutInfo.viewportStartOffset
                    val item = list.layoutInfo.visibleItemsInfo.firstOrNull {
                        x >= it.offset && x <= it.offset + it.size &&
                            ordered.any { category -> it.key == "category:${category.id}" }
                    }
                    dragging = ordered.firstOrNull { item?.key == "category:${it.id}" }?.id
                    dragCenter = if (item == null) x else item.offset + item.size / 2f
                    dragTravel = 0f
                    dragMoved = false
                    if (dragging != null) haptics.play(FolioHaptic.Commit)
                },
                onDrag = { change, amount ->
                    if (dragging != null) {
                        change.consume()
                        dragTravel += amount.x
                        dragCenter += amount.x * layoutSign
                        moveOverNeighbor()
                    }
                },
                onDragEnd = { finishDrag(cancelled = false) },
                onDragCancel = { finishDrag(cancelled = true) },
            )
        },
        contentPadding = PaddingValues(horizontal = FolioTokens.gutter),
        horizontalArrangement = Arrangement.spacedBy(8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        if (leading != null) item(key = "mode-switch") { leading() }
        items(ordered, key = { "category:${it.id}" }) { category ->
            val held = dragging == category.id
            Box(Modifier.zIndex(if (held) 1f else 0f).then(if (held || !motion) Modifier else Modifier.animateItem())
                .graphicsLayer {
                    if (held) {
                        val item = list.layoutInfo.visibleItemsInfo.firstOrNull { it.key == "category:${category.id}" }
                        translationX = layoutSign * (dragCenter - ((item?.offset ?: 0) + (item?.size ?: 0) / 2f))
                        scaleX = if (motion) 1.06f else 1f
                        scaleY = if (motion) 1.06f else 1f
                    }
                }.onGloballyPositioned { anchors[category.id] = it.boundsInWindow() }
                .semantics {
                    customActions = listOf(
                        CustomAccessibilityAction("Manage ${category.name}") { menuId = category.id; true },
                        CustomAccessibilityAction("Move earlier") { moveFromMenu(category.id, -1); true },
                        CustomAccessibilityAction("Move later") { moveFromMenu(category.id, 1); true },
                    )
                }) {
                FolioChip(selected = selectedId == category.id, onClick = { onSelect(category.id) }, label = category.name)
            }
        }
        item(key = "edit-categories") { FolioChip(false, onEdit, "Edit") }
        if (trailing != null) item(key = "category-tools") { trailing() }
    }

    val active = ordered.firstOrNull { it.id == menuId }
    FolioMenu(expanded = active != null, onDismissRequest = { menuId = null }, anchor = active?.let { anchors[it.id] }, title = active?.name) {
        if (active != null) {
            FolioMenuItem(text = { Text("Rename") }, onClick = {
                name = active.name; renameId = active.id; menuId = null
            })
            FolioMenuItem(text = { Text("Move earlier") }, enabled = ordered.firstOrNull()?.id != active.id,
                onClick = { moveFromMenu(active.id, -1) })
            FolioMenuItem(text = { Text("Move later") }, enabled = ordered.lastOrNull()?.id != active.id,
                onClick = { moveFromMenu(active.id, 1) })
            FolioMenuItem(text = { Text("Delete category") }, enabled = active.canDelete, onClick = {
                menuId = null
                if (onDeleteRequest != null) onDeleteRequest(active.id) else deleteId = active.id
            })
        }
    }
    if (renameId != null) FolioDialog(true, { renameId = null }, title = "Rename category") {
        OutlinedTextField(value = name, onValueChange = { name = it }, singleLine = true,
            label = { Text("Name") }, modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { renameId = null }) { Text("Cancel") }
            TextButton(enabled = name.isNotBlank(), onClick = { onRename(requireNotNull(renameId), name.trim()); renameId = null }) { Text("Save") }
        }
    }
    val deleting = ordered.firstOrNull { it.id == deleteId }
    if (deleting != null) FolioDialog(true, { deleteId = null }, title = "Delete category?") {
        Text("Delete '${deleting.name}'? Items will stay in your library.", Modifier.padding(horizontal = 16.dp))
        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.End) {
            TextButton(onClick = { deleteId = null }) { Text("Cancel") }
            TextButton(onClick = { onDelete(deleting.id); deleteId = null }) { Text("Delete") }
        }
    }
}
