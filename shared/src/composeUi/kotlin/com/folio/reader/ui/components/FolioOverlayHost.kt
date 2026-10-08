package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.folio.reader.ui.theme.FolioGlassTier
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSurfaceContents
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioSectionAccent
import com.folio.reader.ui.theme.atmosphere
import kotlin.math.roundToInt

/**
 * §16 liquid glass — **the in-window overlay layer** (issue 3: opaque menus and dialogs).
 *
 * ## The problem this exists for
 *
 * `DropdownMenu` and `AlertDialog` are rendered by Compose in a **separate window** (a `Popup` /
 * `Dialog` window, or the platform's own `AlertDialog`). That has two consequences, and together
 * they are why every menu in the app read as an opaque box:
 *
 *  1. The Haze capture cannot cross the window boundary. Not for the reason it looks like —
 *     `Popup`/`Dialog` content is a *child* composition (`setParentCompositionContext`), so
 *     `LocalGlassBackdrop` and `MaterialTheme` both resolve inside it perfectly well; the proof is
 *     that the opaque fill the reader saw *was* `colorScheme.surfaceContainer`, read through the
 *     boundary. The blocker is one layer down. `HazeSourceNode` records the page into a
 *     `GraphicsLayer` it obtains from `LocalGraphicsContext` — the **content window's** graphics
 *     context, because that is the window whose canvas it draws into (HazeSourceNode.kt). A
 *     `Popup` is a second `AndroidComposeView` with its own `Owner`, its own `GraphicsContext` and
 *     its own draw traversal, so an effect node inside it would be replaying a foreign window's
 *     `RenderNode` into its own display list, one traversal out of step, on top of content the
 *     compositor is already drawing beneath it. Haze offers no synchronisation for that and the
 *     layer's lifetime belongs to the context that created it. So: blur in the app's own window,
 *     or do not blur.
 *  2. The only material left is the fill, and the fill was `colorScheme.surfaceContainer`, i.e.
 *     `atmos.veilFill` at **alpha 0.97**. A lid.
 *
 * A surface that cannot blur can still be *glass*, but only if it is drawn in the app's own
 * window — which is what this host does. It is mounted once, as the last child of the root `Box`
 * in `MainActivity` (inside `FolioGlassRoot`, outside both Haze rungs), so a pane drawn here
 * resolves the **room and the page** exactly as the masthead does, and real blur works.
 *
 * ## What it is not
 *
 * Not a `Popup`, not a `Dialog`, not a platform window. There is no second window, no
 * `WindowManager` token, and no focus transfer: the pane is a child of the app's own root `Box`.
 * That is the whole point, and it is also where the work is — the host owns back handling, the
 * scrim, the scroll, and the keyboard inset (`imePadding` on the dialog and sheet placements, so a
 * pane with a text field clears the IME that no dialog window is resizing for it). Only *initial*
 * focus is still the caller's to arrange, if it wants the first field focused on open.
 *
 * ## Migration
 *
 * Call sites move one at a time, through the [FolioMenu] / [FolioDialog] adapters, which keep the
 * `expanded` + `onDismissRequest` shape a `DropdownMenu`/`AlertDialog` call site already has — so
 * the migration is adding an `anchor` and deleting the Material scaffolding, not rewriting the
 * screen. On a tree with no host (desktop, previews, tests) the adapters fall back to the
 * Material component, so those keep exactly what they had.
 */

/** Which placement the host should give a pane. */
enum class FolioOverlayKind {
    /** Anchored to a trigger's bounds — a context menu, a filter menu, a "more" menu. */
    Menu,

    /** Centred, with the scrim behind it — a confirmation or a small form. */
    Dialog,

    /** Pinned to the bottom edge, full width — a bottom sheet. */
    Sheet,
}

/**
 * The open-pane state, owned by the host and reached through [LocalFolioOverlay].
 *
 * Deliberately a plain state holder rather than a `CompositionLocal` of the *pane*: the host has
 * to be the one that decides placement, scrim and dismissal, and it can only do that if it owns
 * the state. Callers publish intent (`showMenu`/`showDialog`/`showSheet`) and are told when the
 * pane is dismissed.
 *
 * `content` composes in the **host's** scope, not the caller's, so a pane that reads a
 * caller-scoped composition local (a section accent, a preview override) will not see it — pass
 * such values in as parameters. It receives a [ColumnScope] so menu items keep the receiver their
 * Material equivalents expect.
 */
@Stable
class FolioOverlayController internal constructor() {
    internal var kind by mutableStateOf<FolioOverlayKind?>(null)
        private set
    internal var anchor by mutableStateOf<Rect?>(null)
        private set
    internal var onDismiss: (() -> Unit)? = null
    internal var pane: (@Composable ColumnScope.() -> Unit)? = null
    internal var title: String? = null

    /** True while a pane is showing. The host's back handler is gated on this. */
    val isOpen: Boolean get() = kind != null

    /**
     * Show a menu anchored to [anchor] — the trigger's bounds **in window coordinates**
     * (`Modifier.onGloballyPositioned { it.boundsInWindow() }`).
     */
    fun showMenu(
        anchor: Rect,
        title: String? = null,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Menu, anchor, title, onDismiss, content)

    /** Show a centred dialog. */
    fun showDialog(
        title: String? = null,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Dialog, null, title, onDismiss, content)

    /** Show a bottom sheet. */
    fun showSheet(
        title: String? = null,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Sheet, null, title, onDismiss, content)

    private fun show(
        kind: FolioOverlayKind,
        anchor: Rect?,
        title: String?,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
    ) {
        this.kind = kind
        this.anchor = anchor
        this.title = title
        this.onDismiss = onDismiss
        this.pane = content
    }

    /**
     * Close the pane and tell the caller. Idempotent: the scrim tap, the back gesture and an
     * explicit `dismiss()` can all land in the same frame, and only the first should notify.
     */
    fun dismiss() {
        val notify = onDismiss ?: return
        kind = null
        anchor = null
        title = null
        onDismiss = null
        pane = null
        notify()
    }
}

/**
 * The controller, or null on a tree with no host (desktop, previews, tests). Callers that need a
 * no-op fallback should read it as nullable and keep their old path — that is what keeps an
 * un-hosted tree byte-for-byte what it was, and it is what the [FolioMenu] / [FolioDialog]
 * adapters do.
 */
val LocalFolioOverlay = compositionLocalOf<FolioOverlayController?> { null }

/** The app's single controller. Remembered at the host; published through [LocalFolioOverlay]. */
@Composable
fun rememberFolioOverlayController(): FolioOverlayController = remember { FolioOverlayController() }

/**
 * Mount the overlay layer. **Exactly one per app**, as the last child of the root `Box` in
 * `MainActivity` — after `FolioNavShell` (so it draws over the chrome), inside `FolioGlassRoot`
 * (so it has a backdrop) and outside both Haze rungs (so it can sample them).
 *
 * The pane is drawn with [folioVeil] at [FolioGlassTier.Overlay] — the same material as the
 * masthead and the capsule — so it blurs the room and the page under it. When the chrome is
 * occluding a tab swap ([LocalChromeOccludeSwap]) the blur is dropped and the solid fill stands,
 * matching the masthead's own fallback.
 */
@Composable
fun FolioOverlayHost(modifier: Modifier = Modifier) {
    val controller = LocalFolioOverlay.current ?: return
    val kind = controller.kind ?: return
    val pane = controller.pane ?: return
    val title = controller.title
    val anchor = controller.anchor

    val scrim = FolioTheme.atmosphere.shadowAmbient.copy(alpha = FOLIO_OVERLAY_SCRIM_ALPHA)
    // The pane must not blur while the chrome is occluding a swap: the backdrop would be the
    // outgoing tab, and a menu blurring the previous screen is worse than a solid menu.
    val occlude = LocalChromeOccludeSwap.current
    val scrimInteraction = remember { MutableInteractionSource() }
    val paneInteraction = remember { MutableInteractionSource() }

    // Back closes the pane, and only while one is open — the handler lives inside the host, so it
    // sits above the shell's own walk in the handler stack and cannot swallow a back that was
    // meant to pop a route.
    FolioBackHandler(enabled = true, onBack = { controller.dismiss() })

    BoxWithConstraints(
        modifier = modifier.fillMaxSize(),
        contentAlignment = when (kind) {
            FolioOverlayKind.Menu -> Alignment.TopStart
            FolioOverlayKind.Dialog -> Alignment.Center
            FolioOverlayKind.Sheet -> Alignment.BottomCenter
        },
    ) {
        val density = LocalDensity.current
        // The scrim is a *tap target* and a dim, not a blackout. It used to be painted in
        // `folioFieldBottom()` — the field's *own* floor colour — to avoid "a black scrim in a warm
        // room reads as a hole cut in the page". That reasoning was half right and the consequence
        // was the same defect `folioPageWash` shipped: a veil in the colour of the thing it veils
        // cannot dim it. 0.42 of the field's own orange over that orange field flattened the
        // shader's bands and darkened almost nothing, so an open pane never separated from the page.
        //
        // `shadowAmbient` is the correct middle: the palette's own darkness, already paired per
        // polarity upstream (black on a dark face, a deepened `onSurfaceVariant` on a light one), so
        // it dims in the theme's hue instead of punching a hole. See FolioRoomScrim, which paints
        // the always-on ramp from the same source — the two now stack in one colour family.
        Box(
            Modifier
                .matchParentSize()
                .background(scrim)
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = { controller.dismiss() },
                )
        )

        val paneModifier = Modifier
            .then(
                when (kind) {
                    // A menu hangs off its trigger: its top-left tracks the anchor's bottom-left,
                    // clamped into the window so a trigger near an edge cannot push it off screen.
                    FolioOverlayKind.Menu -> {
                        val maxW = with(density) { maxWidth.toPx() }
                        val maxH = with(density) { maxHeight.toPx() }
                        val x = (anchor?.left ?: 0f)
                            .coerceIn(0f, (maxW - FOLIO_OVERLAY_MENU_MIN_PX).coerceAtLeast(0f))
                        val y = (anchor?.bottom ?: 0f)
                            .coerceIn(0f, (maxH - FOLIO_OVERLAY_MENU_MIN_PX).coerceAtLeast(0f))
                        Modifier
                            .offset { IntOffset(x.roundToInt(), y.roundToInt()) }
                            .widthIn(
                                min = FOLIO_OVERLAY_MENU_MIN_WIDTH,
                                max = FOLIO_OVERLAY_MENU_MAX_WIDTH,
                            )
                            // A menu of many rows must scroll rather than run off the screen —
                            // Material's own menu does the same. The cap is what gives the
                            // scroll a bounded height to work against.
                            .heightIn(max = FOLIO_OVERLAY_MENU_MAX_HEIGHT)
                            .padding(FolioTokens.space2)
                    }
                    // A pane with a text field is in the **app's** window, so no dialog window
                    // resizes for the keyboard on its behalf — the host has to. `imePadding`
                    // lengthens the pane's node by the keyboard's height without moving its
                    // content, which is exactly the right lever for both placements: centred, a
                    // node of height `content + ime` always clears the keyboard when the pane
                    // fits the screen at all ((S + H - K)/2 <= S - K reduces to H + K <= S);
                    // pinned to the bottom edge, the content lands directly on top of it. A menu
                    // is left out on purpose: it is placed by an absolute `offset` from its
                    // trigger, so bottom padding would only pad it, not lift it.
                    FolioOverlayKind.Dialog ->
                        Modifier
                            .imePadding()
                            .padding(FolioTokens.space4)
                            .widthIn(max = FOLIO_OVERLAY_DIALOG_MAX_WIDTH)
                    FolioOverlayKind.Sheet -> Modifier.fillMaxWidth().imePadding()
                }
            )
            // The pane swallows taps so a tap inside it does not reach the scrim behind it.
            .clickable(
                interactionSource = paneInteraction,
                indication = null,
                onClick = {},
            )
            .folioVeil(
                shape = when (kind) {
                    FolioOverlayKind.Sheet -> FolioShapes.sheet
                    else -> FolioShapes.card
                },
                accent = LocalFolioSectionAccent.current,
                // §16 cost model: an anchored menu is small, high-attention chrome under the
                // reader's finger, so it takes the finer `control` budget; a dialog or a
                // sheet is a pane and takes the pane budget. See FolioGlassCost.
                glass = when (kind) {
                    FolioOverlayKind.Menu -> GlassSpec.Control
                    else -> GlassSpec.Default
                }.copy(blurEnabled = !occlude),
                tier = FolioGlassTier.Overlay,
            )
            .then(
                if (title != null) {
                    Modifier.semantics { paneTitle = title }
                } else {
                    Modifier
                }
            )

        // Nested panels inside the pane are quieted — a card inside the glass must not ring a
        // second full-strength rim a few pixels inside the pane's own (see FolioSurfaceDepth).
        // The pane scrolls so a long menu or dialog is usable rather than clipped.
        FolioSurfaceContents {
            Column(
                paneModifier.then(Modifier.verticalScroll(rememberScrollState())),
            ) {
                // A pane's title is drawn *here*, not by the caller: it is part of the pane's
                // furniture (it also becomes the accessibility `paneTitle` above), so a caller that
                // passes one gets it without also having to remember to render it.
                if (title != null) {
                    Text(
                        text = title,
                        style = FolioTheme.typography.titleSmall,
                        color = FolioTheme.colors.onSurface,
                        modifier = Modifier.padding(
                            start = FolioTokens.space3,
                            end = FolioTokens.space3,
                            top = FolioTokens.space2,
                            bottom = FolioTokens.space1,
                        ),
                    )
                }
                pane()
            }
        }
    }
}

/**
 * A menu that uses the in-window host when one is mounted and Material's own `DropdownMenu`
 * (a separate window — the pre-P5 behaviour) when it is not.
 *
 * Deliberately shaped like `DropdownMenu` so a call site migrates by renaming the call and
 * deleting the Material-only arguments: `expanded`/`onDismissRequest` keep their meaning, and the
 * content is a [ColumnScope] block so `DropdownMenuItem`s go in unchanged.
 *
 * **Anchoring is automatic.** Material's own `DropdownMenu` anchors to its *parent layout's*
 * origin, which is why the idiom is `Box { Trigger(); DropdownMenu(…) }`. This does the same
 * thing the same way: a zero-size marker at the call site's own position becomes the pane's
 * anchor, so a `Box`-sibling menu hangs off the trigger's origin and a `Column`-sibling (the
 * per-item context menus) lands just under its trigger. No call site has to plumb bounds.
 * [anchor] overrides it for the rare menu that wants to point somewhere else.
 */
@Composable
fun FolioMenu(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    anchor: Rect? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalFolioOverlay.current
    if (controller == null) {
        androidx.compose.material3.DropdownMenu(
            expanded = expanded,
            onDismissRequest = onDismissRequest,
            modifier = modifier,
        ) {
            content()
        }
        return
    }
    // The self-anchor marker. Zero-size, so it costs a layout node and nothing else. It reports
    // its **parent's** bounds, not its own — because that is exactly what Material's own
    // `DropdownMenu` anchors to (its parent layout's bottom-left), so a migrated call site lands
    // where the Material menu did rather than a trigger-height above it. A site whose parent is
    // the wrong thing was already wrong under Material; nothing new is introduced here.
    var selfAnchor by remember { mutableStateOf<Rect?>(null) }
    Box(
        Modifier.onGloballyPositioned { coords ->
            selfAnchor = (coords.parentLayoutCoordinates ?: coords).boundsInWindow()
        }
    )
    val rect = anchor ?: selfAnchor ?: Rect.Zero

    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    // Ownership: only the menu that actually opened the pane may close it. Without this, a second
    // `FolioMenu` composing with `expanded = false` would dismiss the first one's pane.
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(expanded, rect) {
        if (expanded) {
            shown = true
            controller.showMenu(rect, title = title, onDismiss = { currentDismiss() }) {
                currentContent()
            }
        } else if (shown) {
            shown = false
            controller.dismiss()
        }
    }
    // A menu composed conditionally — `if (open) FolioMenu(expanded = true, …)` — leaves the
    // composition without ever seeing `expanded` go false, so the pane has to be closed on dispose
    // or it would outlive its trigger. The `shown` guard keeps this from closing a pane another
    // menu owns.
    DisposableEffect(Unit) {
        onDispose { if (shown) controller.dismiss() }
    }
}

/**
 * A dialog that uses the in-window host when one is mounted and Material's `AlertDialog` when it
 * is not. Same contract as [FolioMenu].
 */
@Composable
fun FolioDialog(
    expanded: Boolean,
    onDismissRequest: () -> Unit,
    modifier: Modifier = Modifier,
    title: String? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalFolioOverlay.current
    if (controller == null) {
        androidx.compose.material3.AlertDialog(
            onDismissRequest = onDismissRequest,
            confirmButton = {},
            title = title?.let { t -> { androidx.compose.material3.Text(t) } },
            // `content` wants a ColumnScope receiver (so menu items keep theirs); Material's
            // `text` slot has none, so it is supplied here.
            text = { Column { content() } },
            modifier = modifier,
        )
        return
    }
    val currentContent by rememberUpdatedState(content)
    val currentDismiss by rememberUpdatedState(onDismissRequest)
    var shown by remember { mutableStateOf(false) }
    LaunchedEffect(expanded) {
        if (expanded) {
            shown = true
            controller.showDialog(title = title, onDismiss = { currentDismiss() }) {
                currentContent()
            }
        } else if (shown) {
            shown = false
            controller.dismiss()
        }
    }
    // See FolioMenu: a dialog composed conditionally must close its pane when it leaves.
    DisposableEffect(Unit) {
        onDispose { if (shown) controller.dismiss() }
    }
}

/**
 * The scrim's alpha. Low: it must dim the room without reading as a blackout.
 *
 * Stepped down from 0.42 when the scrim's colour moved from `folioFieldBottom()` to
 * `shadowAmbient`. 0.42 was tuned against a fill that *could not dim* — the field's own colour over
 * the field — so the number was compensating for a colour bug rather than describing a dim. Now
 * that the colour darkens for real, and now that `FolioRoomScrim` lays an always-on 0.10–0.14 ramp
 * underneath it, 0.42 would stack to ~0.50 and read as a blackout. 0.32 stacks to ~0.40, which is
 * the conventional modal-scrim band and the first value at which an open pane actually separates
 * from the page.
 */
private const val FOLIO_OVERLAY_SCRIM_ALPHA = 0.32f

/** A menu pane's width ceiling — a menu is a list of short labels, not a panel. */
private val FOLIO_OVERLAY_MENU_MAX_WIDTH = 320.dp

/** A menu pane's width floor, so a menu of two-word labels is not a cramped sliver. */
private val FOLIO_OVERLAY_MENU_MIN_WIDTH = 200.dp

/** A menu pane's height ceiling; past it the pane scrolls, as Material's own menu does. */
private val FOLIO_OVERLAY_MENU_MAX_HEIGHT = 420.dp

/** The dialog pane's width ceiling, so a small confirm does not become a full-width slab. */
private val FOLIO_OVERLAY_DIALOG_MAX_WIDTH = 420.dp

/** Clamp guard: keep at least this much of a menu pane on screen when the anchor is at an edge. */
private const val FOLIO_OVERLAY_MENU_MIN_PX = 96f
