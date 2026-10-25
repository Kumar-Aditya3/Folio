package com.folio.reader.ui.components

import androidx.compose.animation.core.Animatable
import androidx.compose.animation.core.FastOutLinearInEasing
import androidx.compose.animation.core.LinearOutSlowInEasing
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.imePadding
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.asPaddingValues
import androidx.compose.foundation.layout.ime
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.TransformOrigin
import androidx.compose.ui.graphics.graphicsLayer
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.layout
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.paneTitle
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.IntOffset
import com.folio.reader.ui.theme.FolioGlassTier
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioSurfaceContents
import com.folio.reader.ui.theme.FolioSurfaceOpacity
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioSectionAccent
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.rememberMotionEnabled
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.launch
import kotlin.math.roundToInt
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

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
 * One open pane in the host's stack.
 *
 * Deliberately a plain data holder rather than a `CompositionLocal` of the *pane*: the host has
 * to decide placement, scrim and dismissal, and it can only do that if it owns the state.
 * Callers publish intent (`showMenu`/`showDialog`/`showSheet`) and are told when the pane is
 * dismissed.
 *
 * `content` composes in the **host's** scope, not the caller's, so a pane that reads a
 * caller-scoped composition local (a section accent, a preview override) will not see it — pass
 * such values in as parameters. It receives a [ColumnScope] so menu items keep the receiver their
 * Material equivalents expect.
 */
@Stable
internal class FolioOverlayEntry(
    val token: Int,
    val kind: FolioOverlayKind,
    // Mutable: a live menu's trigger can move (state changes above it re-measure the
    // bar) and the menu must follow it — see [FolioOverlayController.updateAnchor].
    var anchor: Rect?,
    val title: String?,
    fillAlpha: Float?,
    blurEnabled: Boolean,
    val onDismiss: (() -> Unit)?,
    val pane: @Composable ColumnScope.() -> Unit,
    val readerMaterial: Boolean = false,
) {
    var fillAlpha by mutableStateOf(fillAlpha)
    var blurEnabled by mutableStateOf(blurEnabled)
}

@Stable
class FolioOverlayController internal constructor() {
    // A **stack**, not a slot. It used to hold exactly one pane, and that made every
    // pane a replacement: the source-browse Filters dialog is one hosted pane, and the
    // `Select` filter inside it is a hosted *menu* — opening it called `showMenu`, which
    // clobbered the dialog, and choosing a value left the reader staring at a dropdown
    // with the sheet (and its Apply button) gone — "the filter popup abruptly closes
    // without applying filters". The token-ownership guard only ever stopped a *stale
    // dismiss* from closing a newer pane; it did nothing for a newer *show*. Panes
    // layer in real UIs, so the host holds a stack: a menu pushed over a dialog dims
    // the dialog and closes back into it; dismissing the dialog takes everything above
    // it with it, because its context is gone.
    internal var entries by mutableStateOf(emptyList<FolioOverlayEntry>())
        private set

    // Each `show()` mints a fresh token. A caller dismisses with the token it was
    // given; a dismiss whose token is no longer in the stack is a no-op — so a stale
    // menu can never close a newer pane, regardless of effect ordering.
    private var serial = 0

    /** True while any pane is showing. The host's back handler is gated on this. */
    val isOpen: Boolean get() = entries.isNotEmpty()

    /**
     * Show a menu anchored to [anchor] — the trigger's bounds **in window coordinates**
     * (`Modifier.onGloballyPositioned { it.boundsInWindow() }`).
     */
    fun showMenu(
        anchor: Rect,
        title: String? = null,
        fillAlpha: Float? = null,
        blurEnabled: Boolean = true,
        onDismiss: () -> Unit,
        readerMaterial: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Menu, anchor, title, fillAlpha, blurEnabled, onDismiss, content, readerMaterial)

    /** Show a centred dialog. */
    fun showDialog(
        title: String? = null,
        fillAlpha: Float? = null,
        blurEnabled: Boolean = true,
        onDismiss: () -> Unit,
        readerMaterial: Boolean = false,
        anchor: Rect? = null,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Dialog, anchor, title, fillAlpha, blurEnabled, onDismiss, content, readerMaterial)

    /** Show a bottom sheet. */
    fun showSheet(
        title: String? = null,
        fillAlpha: Float? = null,
        blurEnabled: Boolean = true,
        onDismiss: () -> Unit,
        readerMaterial: Boolean = false,
        content: @Composable ColumnScope.() -> Unit,
    ) = show(FolioOverlayKind.Sheet, null, title, fillAlpha, blurEnabled, onDismiss, content, readerMaterial)

    private fun show(
        kind: FolioOverlayKind,
        anchor: Rect?,
        title: String?,
        fillAlpha: Float?,
        blurEnabled: Boolean,
        onDismiss: () -> Unit,
        content: @Composable ColumnScope.() -> Unit,
        readerMaterial: Boolean,
    ): Int {
        serial += 1
        entries = entries + FolioOverlayEntry(serial, kind, anchor, title, fillAlpha, blurEnabled, onDismiss, content, readerMaterial)
        return serial
    }

    /**
     * Move a live anchored pane's anchor. `FolioMenu` re-fires its effect whenever its
     * trigger re-measures (any state change above it), and under the one-slot host a
     * re-`show` was an idempotent replacement; under the stack it would stack a second,
     * identical menu. A re-show is now an anchor update: same pane, same token, new
     * position. Unknown token: no-op.
     */
    fun updateAnchor(token: Int, anchor: Rect) {
        val index = entries.indexOfFirst { it.token == token }
        if (index < 0) return
        entries[index].anchor = anchor
        // Bump the state so the host's pane re-reads the new anchor this frame.
        entries = entries.toList()
    }

    /** Change a live pane's material without replacing it or disturbing its nested menus. */
    internal fun updateFillAlpha(token: Int, fillAlpha: Float?) {
        entries.firstOrNull { it.token == token }?.fillAlpha = fillAlpha
    }

    internal fun updateBlurEnabled(token: Int, blurEnabled: Boolean) {
        entries.firstOrNull { it.token == token }?.blurEnabled = blurEnabled
    }

    /**
     * Close the topmost pane and tell its caller. Idempotent: the scrim tap, the back
     * gesture and an explicit `dismiss()` can all land in the same frame, and only the
     * first should notify — after the first, there is nothing left to notify.
     */
    fun dismiss() {
        entries.lastOrNull()?.let { dismiss(it.token) }
    }

    /**
     * Close the pane owned by [token] — the value returned by the matching `show*` call —
     * **and everything stacked above it**. A dialog dismissed while a menu is open over
     * it takes the menu with it: the menu's whole context is that dialog. A token that
     * is no longer in the stack (already dismissed, or superseded) is a no-op, which is
     * what lets a menu item open a dialog without the closing menu tearing that dialog
     * straight back down.
     */
    fun dismiss(token: Int) {
        val index = entries.indexOfFirst { it.token == token }
        if (index < 0) return
        val removed = entries.subList(index, entries.size).toList()
        entries = entries.subList(0, index)
        removed.forEach { it.onDismiss?.invoke() }
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

    // ── the exit ──
    //
    // The pane used to appear and vanish as a raw composition add/remove, because this
    // function early-returned on `controller.kind == null`. That return is also why no
    // exit could ever play: by the time the pane should be animating out, the state it
    // would animate is already gone. So the last live pane is *held* — in a plain holder,
    // not in snapshot state, because nothing may recompose off it; it exists only so the
    // exit has something to draw after the controller has cleared itself.
    //
    // The held frame is the stack's top. Popping a pane *into* another pane — a menu
    // closing back into the dialog it was pushed over — plays no exit here: the revealed
    // pane is already composed beneath the one leaving, so the menu simply leaves the
    // stack and the dialog is there. Only the last pane out needs the animation.
    val live = controller.entries
    val open = live.isNotEmpty()
    val exitSpec = rememberMotionAnimation<Float>(MotionKey.OverlayExit)
    val held = remember { FolioOverlayFrame() }
    if (open) held.hold(live.last())
    val exit = remember { Animatable(1f) }
    LaunchedEffect(open) {
        if (open) {
            exit.snapTo(1f)
        } else if (held.kind != null) {
            exit.snapTo(1f)
            exit.animateTo(0f, exitSpec)
        }
    }
    // Closed *and* settled: nothing to draw — and a settled exit must stop composing,
    // because a zero-alpha scrim would still be a full-screen tap target.
    if (!open && !exit.isRunning) return

    // Back closes the topmost pane, and only while one is open — the handler lives inside
    // the host, so it sits above the shell's own walk in the handler stack and cannot
    // swallow a back that was meant to pop a route. Disabled during the exit so a second
    // back reaches the route rather than being eaten by a pane that is already leaving.
    FolioBackHandler(enabled = open, onBack = { controller.dismiss() })

    BoxWithConstraints(modifier = modifier.fillMaxSize()) {
        // The stack, drawn bottom-up. Each pane owns a scrim and a tap shield. A pane
        // pushed over another pane — the source-browse Filters dialog's Select menu is
        // the case that forced the stack — dims the pane under it only lightly (the
        // under-pane already carries the full scrim against the page), and its shield
        // swallows the tap, so an outside tap closes the menu first and *reveals* the
        // dialog instead of punching through to it.
        live.forEachIndexed { index, entry ->
            key(entry.token) {
                val frame = rememberEntryFrame(entry)
                // Refresh the paint state every composition — the entry's anchor can move
                // under a live menu (updateAnchor), and the held-exit path relies on the
                // same copy happening while the entry is still live. The `remember` inside
                // rememberEntryFrame only pins the instance identity, which is what keeps
                // the layout-written `originY` across frames.
                frame.hold(entry)
                OverlayPaneFrame(
                    frame = frame,
                    scrimAlpha = if (index == 0) {
                        FOLIO_OVERLAY_SCRIM_ALPHA
                    } else {
                        FOLIO_OVERLAY_STACKED_SCRIM_ALPHA
                    },
                    onScrimTap = { controller.dismiss(entry.token) },
                    animateIn = true,
                )
            }
        }

        // The held pane, animating out over whatever is beneath it (usually nothing).
        if (!open) {
            OverlayPaneFrame(
                frame = held,
                scrimAlpha = FOLIO_OVERLAY_SCRIM_ALPHA,
                onScrimTap = {},
                animateIn = false,
                fade = exit.value,
                grow = FOLIO_OVERLAY_MENU_ENTER_SCALE +
                    (1f - FOLIO_OVERLAY_MENU_ENTER_SCALE) * exit.value,
            )
        }
    }
}

/**
 * One pane of the overlay stack: its scrim, its tap shield, and the veil-drawn pane itself.
 *
 * [animateIn] runs the pane's own entrance (fade + grow out of its kind's transform origin);
 * the exit path passes `false` and drives [fade]/[grow] from the host's exit animator
 * instead, because the exiting pane's composition is already detached from the controller.
 */
@Composable
private fun BoxScope.OverlayPaneFrame(
    frame: FolioOverlayFrame,
    scrimAlpha: Float,
    onScrimTap: () -> Unit,
    animateIn: Boolean,
    fade: Float = 1f,
    grow: Float = 1f,
) {
    val kind = frame.kind ?: return
    val pane = frame.pane ?: return
    val title = frame.title
    val anchor = frame.anchor
    val paneFillAlpha = frame.fillAlpha

    // Asymmetric on purpose, and this is the lean: opening is the acknowledgement of a
    // tap, so it may take the time to arrive from the trigger; closing is the user having
    // already decided, so it gets out of the way. A menu that fades out as slowly as it
    // faded in feels like latency.
    // The pane's arrival comes from the motion table, where the duration that used to be a
    // private constant (160 in) is one row alongside every other animation in the app, and
    // reduce-motion shortens it rather than snapping it. (The exit runs on the host's own
    // animator above, on the table's `OverlayExit` row.)
    val enterSpec = rememberMotionAnimation<Float>(MotionKey.OverlayEnter)
    val growEnterSpec = rememberMotionAnimation<Float>(MotionKey.SheetEnter)
    val fadeAnim = remember(frame.token) { Animatable(if (animateIn) 0f else 1f) }
    val growAnim = remember(frame.token) {
        Animatable(if (animateIn) FOLIO_OVERLAY_MENU_ENTER_SCALE else 1f)
    }
    if (animateIn) {
        LaunchedEffect(frame.token) {
            coroutineScope {
                launch { fadeAnim.animateTo(1f, enterSpec) }
                launch { growAnim.animateTo(1f, growEnterSpec) }
            }
        }
    }
    val paneAlpha = if (animateIn) fadeAnim.value else fade
    val paneScale = if (animateIn) growAnim.value else grow

    val occlude = LocalChromeOccludeSwap.current
    val scrimInteraction = remember { MutableInteractionSource() }
    val paneInteraction = remember { MutableInteractionSource() }

    BoxWithConstraints(
        modifier = Modifier.matchParentSize(),
        contentAlignment = when (kind) {
            FolioOverlayKind.Menu -> Alignment.TopStart
            FolioOverlayKind.Dialog -> if (anchor == null) Alignment.Center else Alignment.TopStart
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
                .graphicsLayer { this.alpha = paneAlpha }
                .background(FolioTheme.atmosphere.shadowAmbient.copy(alpha = scrimAlpha))
                .clickable(
                    interactionSource = scrimInteraction,
                    indication = null,
                    onClick = onScrimTap,
                )
        )

        val paneModifier = Modifier
            .then(
                when (kind) {
                    // A menu hangs off its trigger: its top-left tracks the anchor's
                    // bottom-left — measured first, then placed, so the clamp knows the
                    // pane's real size and a trigger near any edge cannot push it off
                    // screen (see the placement comment below).
                    FolioOverlayKind.Menu -> {
                        val maxW = with(density) { maxWidth.toPx() }
                        val maxH = with(density) { maxHeight.toPx() }
                        Modifier
                            .widthIn(
                                min = FOLIO_OVERLAY_MENU_MIN_WIDTH,
                                max = FOLIO_OVERLAY_MENU_MAX_WIDTH,
                            )
                            // A menu of many rows must scroll rather than run off the screen —
                            // Material's own menu does the same. The cap is what gives the
                            // scroll a bounded height to work against.
                            .heightIn(max = FOLIO_OVERLAY_MENU_MAX_HEIGHT)
                            // Placement that measures before it places. The old `offset`
                            // clamped the pane's top-left against a fixed 96px guard without
                            // knowing the pane's real size, so a menu anchored near the right
                            // edge (panes are 200-320dp wide) or near the bottom of the
                            // screen (heights run to the 420dp cap) was pushed past the
                            // window and cut off by it — the long-press menus on the last
                            // shelf rows. Clamping with the measured size fixes the sides;
                            // the flip — open above the trigger when the menu does not fit
                            // below it — fixes the foot, the same way Material's own menus
                            // flip; and the final coerce is the degenerate fallback (a pane
                            // taller than the window pins to the top and scrolls).
                            .layout { measurable, constraints ->
                                val placeable = measurable.measure(constraints)
                                val maxX = (maxW - placeable.width).coerceAtLeast(0f).roundToInt()
                                val x = (anchor?.left ?: 0f).roundToInt().coerceIn(0, maxX)
                                val top = (anchor?.top ?: 0f).roundToInt()
                                val below = (anchor?.bottom ?: 0f).roundToInt()
                                val y = when {
                                    below + placeable.height <= maxH.roundToInt() -> below
                                    top - placeable.height >= 0 -> top - placeable.height
                                    else -> (maxH - placeable.height).coerceAtLeast(0f).roundToInt()
                                }
                                // Which end the pane grew from, for the transform origin
                                // below. A menu that flipped above its trigger has to
                                // scale out of its *bottom* edge or it appears to crawl
                                // away from the finger that opened it. Written in layout,
                                // read in draw — the phases are already in that order, so
                                // no extra frame and no recomposition.
                                frame.originY = if (y < top) 1f else 0f
                                layout(placeable.width, placeable.height) {
                                    placeable.place(x, y)
                                }
                            }
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
                    FolioOverlayKind.Dialog -> if (anchor != null) {
                        val keyboard = WindowInsets.ime.asPaddingValues().calculateBottomPadding()
                        val available = (maxHeight - keyboard).coerceAtLeast(0.dp)
                        val maxW = with(density) { maxWidth.toPx().roundToInt() }
                        val maxH = with(density) { available.toPx().roundToInt() }
                        val margin = with(density) { 12.dp.toPx().roundToInt() }
                        Modifier.widthIn(max = FOLIO_OVERLAY_DIALOG_MAX_WIDTH)
                            .heightIn(max = available)
                            .layout { measurable, constraints ->
                                val pane = measurable.measure(constraints)
                                val point = folioContextDialogPosition(anchor, pane.width, pane.height, maxW, maxH, margin)
                                frame.originY = if (point.y < anchor.top) 1f else 0f
                                layout(pane.width, pane.height) { pane.place(point.x, point.y) }
                            }.padding(FolioTokens.space4)
                    } else Modifier
                            .imePadding()
                            .padding(FolioTokens.space4)
                            .widthIn(max = FOLIO_OVERLAY_DIALOG_MAX_WIDTH)
                            // A dialog whose own content is scrollable (a LazyColumn or a
                            // nested verticalScroll — e.g. a source's Filters sheet) would be
                            // measured with infinite max-height by the pane's verticalScroll
                            // below and crash ("Vertically scrollable component was measured
                            // with an infinity maximum height constraints"). Capping to the
                            // window height keeps the measurement finite; the pane itself
                            // scrolls when the content is taller.
                            .heightIn(max = maxHeight)
                    FolioOverlayKind.Sheet ->
                        Modifier.fillMaxWidth().imePadding().heightIn(max = maxHeight)
                }
            )
            // The transition, applied *after* the placement modifiers so it transforms the
            // pane in place and the measure-then-place clamp above is untouched. A menu
            // grows out of the corner it hangs from — the trigger's own corner, start-side,
            // and the end the placement actually chose (see `originY`) — which is what makes
            // the pane read as having come *from* the control rather than having been
            // cross-faded over the screen. A dialog and a sheet scale about their own middle
            // and foot respectively, matching where each one enters from.
            .graphicsLayer {
                this.alpha = paneAlpha
                scaleX = paneScale
                scaleY = paneScale
                transformOrigin = when (kind) {
                    FolioOverlayKind.Menu -> TransformOrigin(0f, frame.originY)
                    FolioOverlayKind.Dialog -> TransformOrigin(0.5f, if (anchor == null) 0.5f else frame.originY)
                    FolioOverlayKind.Sheet -> TransformOrigin(0.5f, 1f)
                }
            }
            // The pane swallows taps so a tap inside it does not reach the scrim behind it.
            .clickable(
                interactionSource = paneInteraction,
                indication = null,
                onClick = {},
            )
            .then(if (frame.readerMaterial) {
                Modifier.folioReaderGlass(FolioShapes.card, paneFillAlpha ?: FolioSurfaceOpacity.POPUP_GLASS,
                    blurEnabled = !occlude && frame.blurEnabled)
            } else Modifier.folioDataGlass(
                shape = when (kind) {
                    FolioOverlayKind.Sheet -> FolioShapes.sheet
                    else -> FolioShapes.card
                },
                // Every hosted pop-up (dialog, menu, sheet) is detached from the user
                // opacity knobs — a pop-up must not go see-through however transparent the
                // room's chrome is set — but it is glass, not the lid it shipped as: it
                // paints at its own designed glass point, just above the panel's blurred
                // tier, so it reads as the same material as the chrome around it. A caller
                // may still pin an explicit alpha (the reader's in-reader-controls veil).
                fillAlpha = paneFillAlpha ?: FolioSurfaceOpacity.POPUP_GLASS,
                // Shared data-glass diffusion keeps pop-ups in the same material family.
                // Reader hosts can suppress backdrop capture when it would include an occluded view.
                overlay = true,
                blurEnabled = !occlude && frame.blurEnabled,
            ))
            .then(
                if (title != null) {
                    Modifier.semantics { paneTitle = title }
                } else {
                    Modifier
                }
            )

        // Nested panels inside the pane are quieted — a card inside the glass must not ring a
        // second full-strength rim a few pixels inside the pane's own (see FolioSurfaceDepth).
        // The pane scrolls so a long menu or dialog is usable rather than clipped. §19: the
        // pane publishes the exact data-glass plane it is painted with (same alpha and
        // blur gating as above), so nested surfaces are a floor
        // of this pane's material plus a rim: one object with structure in it.
        FolioSurfaceContents(
            folioDataGlassPlane(paneFillAlpha ?: FolioSurfaceOpacity.POPUP_GLASS,
                blurEnabled = !occlude && frame.blurEnabled)
        ) {
            // Dialog panes carry the content inset themselves. Their callers compose
            // bare rows and texts (`PageActionsDialog`, the confirm dialogs, the reader
            // settings), and the pane's rim and edge ramp painted right up against that
            // content — the first and last words of every row sat under the pane's own
            // edge, which read as clipped. Menu rows own their content padding
            // ([FolioMenuItem]) and sheets are full-bleed by design, so only the dialog
            // kind is inset here.
            val contentInset = if (kind == FolioOverlayKind.Dialog) {
                Modifier.padding(
                    start = FolioTokens.space3,
                    end = FolioTokens.space3,
                    top = if (title == null) FolioTokens.space2 else 0.dp,
                    bottom = FolioTokens.space3,
                )
            } else {
                Modifier
            }
            Column(
                paneModifier
                    .then(contentInset)
                    .then(Modifier.verticalScroll(rememberScrollState())),
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
                            // The dialog pane itself now carries the horizontal inset; a
                            // second one would indent the title relative to its own rows.
                            start = if (kind == FolioOverlayKind.Dialog) 0.dp else FolioTokens.space3,
                            end = if (kind == FolioOverlayKind.Dialog) 0.dp else FolioTokens.space3,
                            top = FolioTokens.space2,
                            bottom = FolioTokens.space1,
                        ),
                    )
                }
                FolioSuppressibleBackdrop(suppress = !frame.blurEnabled) { pane() }
            }
        }
    }
}

/** The frame for one live stack entry, remembered per token so its layout-written state persists. */
@Composable
private fun rememberEntryFrame(entry: FolioOverlayEntry): FolioOverlayFrame =
    remember(entry.token) { FolioOverlayFrame().apply { hold(entry) } }

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
    fillAlpha: Float? = null,
    readerMaterial: Boolean = false,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalFolioOverlay.current
    val blurEnabled = LocalGlassBackdrop.current != null
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
    // Ownership: only the menu that actually opened the pane may close it. `showMenu` returns a
    // token; dismissing with it is a no-op once that pane has left the stack — which is what
    // lets a menu item open a dialog (the dialog stacks *above* the menu) without the closing
    // menu tearing the newer pane straight back down.
    var token by remember { mutableStateOf(0) }
    LaunchedEffect(expanded, rect) {
        if (expanded) {
            // The effect re-fires whenever the trigger re-measures (any state change above
            // it — a count in the label, the bar's own recomposition). Under the stack a
            // second `showMenu` would stack a second identical menu, so a live menu moves
            // by anchor update; only the first fire opens the pane.
            if (token != 0) {
                controller.updateAnchor(token, rect)
            } else {
                token = controller.showMenu(rect, title = title, fillAlpha = fillAlpha, blurEnabled = blurEnabled, readerMaterial = readerMaterial, onDismiss = { currentDismiss() }) {
                    currentContent()
                }
            }
        } else if (token != 0) {
            controller.dismiss(token)
            token = 0
        }
    }
    // A menu composed conditionally — `if (open) FolioMenu(expanded = true, …)` — leaves the
    // composition without ever seeing `expanded` go false, so the pane has to be closed on dispose
    // or it would outlive its trigger. The token guard keeps this from closing a pane another
    // menu or dialog now owns.
    LaunchedEffect(token, fillAlpha, blurEnabled) {
        if (token != 0) {
            controller.updateFillAlpha(token, fillAlpha)
            controller.updateBlurEnabled(token, blurEnabled)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (token != 0) controller.dismiss(token) }
    }
}

/**
 * The last live pane, held across its own exit.
 *
 * Ownership and placement are retained data, copied while the controller has a pane so
 * its exit can still draw after the controller clears. The material properties are
 * snapshot state: their live updates must invalidate the renderer even when this frame
 * retains its identity. [originY] is written from layout and read later from draw; that
 * forward phase ordering does not need recomposition. The other retained fields are
 * copied and consumed in the same composition pass.
 */
private class FolioOverlayFrame {
    var token: Int = 0
    var kind: FolioOverlayKind? = null
    var pane: (@Composable ColumnScope.() -> Unit)? = null
    var title: String? = null
    var anchor: Rect? = null
    // The frame keeps its identity while a pane is open. Snapshot properties let the
    // renderer observe live material changes even when Compose skips the parent call.
    var fillAlpha by mutableStateOf<Float?>(null)
    var blurEnabled by mutableStateOf(true)
    var readerMaterial: Boolean = false
    var originY: Float = 0f

    /** Copy a stack entry's paint state in; called while the entry is still live. */
    fun hold(entry: FolioOverlayEntry) {
        token = entry.token
        kind = entry.kind
        pane = entry.pane
        title = entry.title
        anchor = entry.anchor
        fillAlpha = entry.fillAlpha
        blurEnabled = entry.blurEnabled
        readerMaterial = entry.readerMaterial
    }
}

/**
 * A menu row at the app's own density.
 *
 * Material's `DropdownMenuItem` carries a **48dp** floor (`MenuListItemContainerHeight`) and
 * 12dp of vertical content padding, and that floor is applied by a `sizeIn` *inside* the
 * component, so it can only be overridden from the call site. It is the right number for a
 * touch menu of four or five destinations; it is the wrong number for the menus this app
 * actually has. The library's display menu is a label, a view switcher, eight sort rows and
 * two direction rows: at 48dp that is over 500dp of pane, which blows through the height cap
 * and becomes a *scrolling* menu — a list of eight short words that the user has to scroll.
 * The long-press menus are three to six rows and read as a slab for the same reason.
 *
 * 36dp with 12dp of horizontal padding keeps every row comfortably past the 32dp that a
 * pointer-and-touch dense menu wants, puts the same eight-row sort list in ~300dp, and stops
 * the cap being reached at all — so the menus stop scrolling, which is most of the "takes way
 * too much space" complaint.
 *
 * Shaped exactly like `DropdownMenuItem` minus `contentPadding` (which it owns), so a call
 * site migrates by renaming the call. [FolioMenuItemLabel] is the matching section label.
 */
@Composable
fun FolioMenuItem(
    text: @Composable () -> Unit,
    onClick: () -> Unit,
    modifier: Modifier = Modifier,
    leadingIcon: (@Composable () -> Unit)? = null,
    trailingIcon: (@Composable () -> Unit)? = null,
    enabled: Boolean = true,
) {
    DropdownMenuItem(
        text = text,
        onClick = onClick,
        modifier = modifier.height(FOLIO_MENU_ROW_HEIGHT),
        leadingIcon = leadingIcon,
        trailingIcon = trailingIcon,
        enabled = enabled,
        contentPadding = PaddingValues(horizontal = FolioTokens.space2, vertical = 0.dp),
    )
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
    fillAlpha: Float? = null,
    readerMaterial: Boolean = false,
    anchor: Rect? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val controller = LocalFolioOverlay.current
    val blurEnabled = LocalGlassBackdrop.current != null
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
    var token by remember { mutableStateOf(0) }
    LaunchedEffect(expanded) {
        if (expanded) {
            token = controller.showDialog(title = title, fillAlpha = fillAlpha, blurEnabled = blurEnabled, readerMaterial = readerMaterial,
                anchor = anchor, onDismiss = { currentDismiss() }) {
                currentContent()
            }
        } else if (token != 0) {
            controller.dismiss(token)
            token = 0
        }
    }
    // See FolioMenu: a dialog composed conditionally must close its pane when it leaves.
    LaunchedEffect(token, fillAlpha, blurEnabled, anchor) {
        if (token != 0) {
            if (anchor != null) controller.updateAnchor(token, anchor)
            controller.updateFillAlpha(token, fillAlpha)
            controller.updateBlurEnabled(token, blurEnabled)
        }
    }
    DisposableEffect(Unit) {
        onDispose { if (token != 0) controller.dismiss(token) }
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

/**
 * The scrim a pane **stacked over another pane** wears. The under-pane already carries
 * [FOLIO_OVERLAY_SCRIM_ALPHA] against the page, so a second full-strength scrim would stack
 * to ~0.40 over the dialog the reader is still working in. A stacked pane only needs enough
 * of a dim to separate it from the pane beneath — the menu over the Filters dialog reads as
 * lifted, not as a second modal.
 */
private const val FOLIO_OVERLAY_STACKED_SCRIM_ALPHA = 0.10f

/** A menu pane's width ceiling — a menu is a list of short labels, not a panel. */
private val FOLIO_OVERLAY_MENU_MAX_WIDTH = 268.dp

/**
 * A menu pane's width floor. Material's own floor for a dropdown row is 112dp
 * (DropdownMenuItemDefaultMinWidth); matching it means the pane sizes to its longest label —
 * "fit to text" — instead of stretching four short scope names across a fixed 200dp slab,
 * while still keeping a two-word menu from being a cramped sliver.
 */
private val FOLIO_OVERLAY_MENU_MIN_WIDTH = 112.dp

/**
 * A menu pane's height ceiling; past it the pane scrolls, as Material's own menu does.
 *
 * Down from 420dp with [FOLIO_MENU_ROW_HEIGHT]: at 36dp a row, 340dp holds nine rows plus a
 * label, which covers every menu in the library outright. The old pairing (48dp rows against
 * a 420dp cap) guaranteed the opposite — the display menus exceeded the cap and shipped as
 * scrolling panes, and a menu tall enough to scroll is a menu that has stopped looking like
 * a menu.
 */
private val FOLIO_OVERLAY_MENU_MAX_HEIGHT = 340.dp

/** See [FolioMenuItem]: the app's menu row height, against Material's 48dp floor. */
private val FOLIO_MENU_ROW_HEIGHT = 36.dp

/**
 * How small a pane starts and ends.
 *
 * 0.96, not 0.8. A menu is anchored chrome a few hundred dp across, and the scale is there to
 * say "this came out of that control", not to perform. Past a few percent the rows visibly
 * reflow inside the growing box and the type swims, which is the cheap-looking end of this
 * effect; 4% reads as the pane settling.
 */
private const val FOLIO_OVERLAY_MENU_ENTER_SCALE = 0.96f

/**
 * The pane's two durations used to live here as `FOLIO_OVERLAY_ENTER_MS` (160) and
 * `FOLIO_OVERLAY_EXIT_MS` (110). They are now the `OverlayEnter` and `OverlayExit` rows of the
 * motion table, where they sit beside every other animation in the app — so the overlay's feel is
 * tunable with everything else instead of being the one transition with its own private constants.
 * `MotionSchemaTest` pins the table to those two values, so the move is provably behaviour-neutral.
 */

/** The dialog pane's width ceiling, so a small confirm does not become a full-width slab. */
private val FOLIO_OVERLAY_DIALOG_MAX_WIDTH = 420.dp

internal fun folioContextDialogPosition(anchor: Rect, paneWidth: Int, paneHeight: Int,
    viewportWidth: Int, viewportHeight: Int, margin: Int): IntOffset {
    val maxX = (viewportWidth - paneWidth - margin).coerceAtLeast(0)
    val maxY = (viewportHeight - paneHeight - margin).coerceAtLeast(0)
    val x = ((anchor.left + anchor.right - paneWidth) / 2).roundToInt().coerceIn(margin.coerceAtMost(maxX), maxX)
    val below = (anchor.bottom + margin).roundToInt()
    val above = (anchor.top - paneHeight - margin).roundToInt()
    val y = when { below <= maxY -> below; above >= margin -> above; else -> maxY }
        .coerceIn(margin.coerceAtMost(maxY), maxY)
    return IntOffset(x, y)
}
