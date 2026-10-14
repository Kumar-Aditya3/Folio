package com.folio.reader.ui.components

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.tween
import androidx.compose.foundation.background
import androidx.compose.foundation.progressSemantics
import androidx.compose.foundation.clickable
import androidx.compose.foundation.combinedClickable
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
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.minimumInteractiveComponentSize
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.material3.TextField
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.selected
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.layout.boundsInWindow
import androidx.compose.ui.layout.onGloballyPositioned
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.foundation.text.KeyboardActions
import androidx.compose.foundation.text.KeyboardOptions
import androidx.compose.ui.text.input.ImeAction
import androidx.compose.ui.text.input.KeyboardType
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.folioLiquidGlass
import com.folio.reader.ui.theme.lightDirection
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioPackId
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.sectionAccent
import com.folio.reader.ui.theme.surfaceOpacity
import com.folio.reader.ui.theme.panelFill

import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.shadow
import androidx.compose.ui.unit.IntOffset
import androidx.compose.foundation.border
import com.folio.reader.ui.theme.MotionKey
import com.folio.reader.ui.theme.rememberMotionAnimation

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
    // Defaults to the current section's signature hue (falls back to `primary`
    // when no section wraps the tree), so a section's cards share its identity
    // while a different section's cards read as a different place. Pass an explicit
    // colour (incl. a cover accent) to override, or `Color.Unspecified`-via-null is
    // no longer the default — callers wanting a strictly neutral card pass
    // `FolioTheme.colors.onSurfaceVariant`.
    accent: Color? = com.folio.reader.ui.theme.FolioTheme.sectionAccent,
    content: @Composable androidx.compose.foundation.layout.ColumnScope.() -> Unit
) {
    Column(
        modifier = modifier
            .fillMaxWidth()
            // Section cards are the dominant large surface on Settings, Home and
            // Stats. They used to be flat `folioPanel` paper — a tint with a rim and
            // nothing behind it — which is exactly the "glass that is just a tint
            // sitting in front of the screen" complaint. Routed through [folioVeil]
            // they become the real liquid-glass material: when the screen registers a
            // backdrop and the device can blur, the moving field is frosted *through*
            // the card (fill steps down to its 0.70 glass tier), and the sheen, edge
            // bevel and AGSL film give it a lit, dimensional body. On a device that
            // cannot blur it degrades to the same flat fill as before, so nothing
            // regresses. The small per-row surfaces stay `folioPanel` on purpose — the
            // blur/film pass is only worth paying on the big surfaces.
            .folioVeil(
                com.folio.reader.ui.theme.FolioShapes.card,
                // A section card is an item in the page's own flow: nothing passes behind it
                // but the ground plane, so it keeps the material and drops the refraction.
                tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
            )
            .padding(com.folio.reader.ui.theme.FolioTokens.space3),
        verticalArrangement = Arrangement.spacedBy(com.folio.reader.ui.theme.FolioTokens.space2)
    ) {
        if (title != null) {
            FolioEyebrow(
                text = title,
                accent = accent ?: com.folio.reader.ui.theme.FolioTheme.colors.onSurfaceVariant,
            )
        }
        // Everything this card contains is one surface deeper than the card — and P7 makes it one
        // *material* too: the card publishes its own plane so a nested panel is painted from the
        // same stuff and separated by its rim alone, instead of stepping to a different tier and
        // reading as a box inside a box. The card is a veil at the in-flow tier, so the plane it
        // publishes is `veilFill` at the panel knob — the alpha it is actually drawn with.
        val scope = this
        com.folio.reader.ui.theme.FolioSurfaceContents(
            com.folio.reader.ui.theme.FolioTheme.atmosphere.veilFill.copy(
                // The alpha the card is actually drawn with: it is a veil at the in-flow tier, so
                // it never blurs and its fill sits at the panel knob.
                alpha = com.folio.reader.ui.theme.FolioTheme.surfaceOpacity.panelFill(canBlur = false),
            ),
        ) { scope.content() }
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
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    // The jacket's hue, clamped to the palette's own chroma, and then budgeted: a
    // caller may ask for a brighter wash but not for one that supplies more of the
    // card's finished colour than the hero's ceiling allows.
    val coverTint = accent?.let { com.folio.reader.ui.theme.tameCover(it, colors.primary) }
    val tint = coverTint ?: colors.accentProgress
    val washAlpha = gradientAlpha.coerceAtMost(
        com.folio.reader.ui.theme.heroWashBorrow(FolioTheme.atmosphere.isDark),
    )
    // §13.7 / M7: the AGSL liquid-glass film, layered under the gradient (and so under
    // the content). It draws only where the platform can
    // (API 33+) and the user's liquid-glass preference is on — the same
    // `specular` capability the veil's edge bevel rides — and is otherwise a
    // no-op, leaving the raised sheen as the intended fallback. The
    // light direction is the room's own virtual sun, so the shader's specular
    // agrees with every other material's catch.
    val atmos = com.folio.reader.ui.theme.FolioTheme.atmosphere
    val daylight = com.folio.reader.ui.theme.LocalFolioDaylight.current
    val (lx, ly) = daylight.lightDirection()
    val glassEnabled = LocalGlassCapabilities.current.specular
    // The plate this card stands on, so the gel refracts the engraving that is actually
    // behind it rather than a default figure cut from a default ramp. A hero floats directly
    // over the ground plane, so the ramp it is cut out of is the atmosphere's own field.
    val platePackId = com.folio.reader.ui.theme.LocalFolioPackId.current
    Box(
        modifier = modifier
            .fillMaxWidth()
            .folioRaised(shape = shape, accent = tint)
            .folioLiquidGlass(
                accent = tint,
                highlight = atmos.rimLight,
                lightX = lx,
                lightY = ly,
                enabled = glassEnabled,
                // R1: the gel is on — this card floats directly over the field, which now
                // carries the guilloche's engraved figure, so the rim has detail to bend
                // where before it had only the ramp. See the hero's own note in HomeScreen.
                refractBackdrop = true,
                shape = shape,
                // The metallic-sheen knob is the film's own intensity scalar, so the
                // hero card's glints dim with every other light catch in the app.
                intensity = com.folio.reader.ui.theme.folioSheenGain(),
                platePackId = platePackId,
                plateFieldTop = atmos.fieldTop,
                plateFieldBottom = atmos.fieldBottom,
            )
            .background(
                brush = Brush.verticalGradient(
                    listOf(tint.copy(alpha = washAlpha), Color.Transparent)
                ),
                shape = shape
            )
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
@OptIn(androidx.compose.foundation.ExperimentalFoundationApi::class)
@Composable
fun FolioChip(
    selected: Boolean,
    onClick: () -> Unit,
    label: String,
    modifier: Modifier = Modifier,
    onLongClick: (() -> Unit)? = null
) {
    val shape = com.folio.reader.ui.theme.FolioShapes.pill
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val atmos = FolioTheme.atmosphere
    val interaction = rememberFolioInteraction()
    // Selection cross-fades rather than switching on one frame — the same idiom
    // the nav capsule and segmented control already use.
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val colorSpec = if (motion) tween<Color>(FolioTokens.motionFast.toInt()) else snap<Color>()
    // Chips/tabs are section chrome: a selected chip wears the section's signature
    // hue, not a universal primary, so the same control reads differently in
    // Library vs Search vs Quotes. Label legibility is guarded against the accent.
    val sectionAccent = FolioTheme.sectionAccent
    val onSectionAccent = com.folio.reader.ui.components.legibleOn(
        colors.onPrimary, sectionAccent, colors.onSurface,
    )
    val fillColor by animateColorAsState(
        if (selected) sectionAccent else atmos.sunkenFill.copy(alpha = 0.55f),
        animationSpec = colorSpec, label = "chipFill",
    )
    val strokeColor by animateColorAsState(
        if (selected) sectionAccent else atmos.hairline,
        animationSpec = colorSpec, label = "chipStroke",
    )
    val labelColor by animateColorAsState(
        if (selected) onSectionAccent else colors.onSurfaceVariant,
        animationSpec = colorSpec, label = "chipLabel",
    )
    Box(
        modifier = modifier
            .folioPressable(interaction, scaleTo = 0.94f)
            .then(
                if (onLongClick == null) {
                    Modifier.clickable(interactionSource = interaction, indication = null, onClick = onClick)
                } else {
                    Modifier.combinedClickable(
                        interactionSource = interaction,
                        indication = null,
                        onClick = onClick,
                        onLongClick = onLongClick
                    )
                }
            )
            // Keep the pill's compact visual height but reserve a >=48dp touch
            // target, so filter/segment chips stay reliably thumb-tappable.
            .minimumInteractiveComponentSize()
            // Selection is spoken, not just coloured: TalkBack now announces the
            // active tab/filter across every FolioChip site.
            .semantics {
                this.selected = selected
                this.role = Role.Tab
            },
        contentAlignment = Alignment.Center
    ) {
        Box(
            modifier = Modifier
                .clip(shape)
                .background(fillColor, shape)
                .border(1.dp, strokeColor, shape)
                .padding(horizontal = 15.dp, vertical = 8.dp)
        ) {
            Text(
                text = label,
                style = MaterialTheme.typography.labelLarge,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
                fontWeight = FontWeight.Medium,
                color = labelColor
            )
        }
    }
}

// FolioTopBar and the Folio mark moved to FolioChrome.kt when the masthead
// gained its scroll-linked collapse (glass on scroll, folding rail). Every call
// site keeps the same signature: the new arguments are defaulted.

/**
 * Ground behind the OS status bar on non-reader screens.
 *
 * Takes the atmosphere's own scrim and fades it out by its bottom edge, so the
 * notification icons get something to sit on without a hard stripe being drawn
 * across the page. A flat fill of the theme's darkest ink is what put a black
 * band over the top of every *light* theme. Zero-height on desktop, where there
 * is no OS bar.
 *
 * [inkBand] keeps that old solid ink fill for the readers: while a page is open
 * the system icons are forced light, so they need dark ground on any theme.
 */
@Composable
fun FolioStatusBarBand(modifier: Modifier = Modifier, inkBand: Boolean = false) {
    val scrim = if (inkBand) {
        com.folio.reader.ui.theme.FolioTheme.colors.statusBar
    } else {
        // Follows the room, not the palette's resting background — see scrimFor.
        val atmos = com.folio.reader.ui.theme.FolioTheme.atmosphere
        com.folio.reader.ui.theme.scrimFor(
            atmos,
            com.folio.reader.ui.theme.LocalFolioAmbientTint.current?.requested,
            atmos.fieldTintStrength,
        )
    }
    Box(
        modifier = modifier
            .fillMaxWidth()
            .windowInsetsTopHeight(WindowInsets.statusBars)
            .background(
                if (inkBand) {
                    Brush.verticalGradient(0f to scrim, 1f to scrim)
                } else {
                    Brush.verticalGradient(
                        0f to scrim,
                        1f to scrim.copy(alpha = scrim.alpha * 0.40f),
                    )
                },
            ),
    )
}

/**
 * Progress as a hairline seam, not a widget. 3dp, squared caps at the leading
 * edge, drawn over a very low-alpha track — it reads as an inlay in the surface
 * rather than another control.
 *
 * **Two authored roles, not one hue faded.** The fill is `progress` and the track is
 * `progressTrack`; both are chosen per face by the art direction, and they are not the
 * same colour at two alphas. `accentProgress` is the *text* form of the palette's progress
 * hue — pushed toward the page's ink only as far as AA requires, because it is read as
 * *text* on `surface` (a section eyebrow, a ring label). `progress` is the drawn form, and
 * a 3dp bar answers to the 3:1 non-text floor instead, measured against its own track.
 * Those are different targets, so on a light face the two roles genuinely diverge; on a
 * dark face they coincide. Measured over all eighteen faces the authored pair separates at
 * 3.3–8.5:1.
 *
 * (The first attempt at consuming the roles drew them exactly as authored and put six of
 * nine light faces at 2.2–2.8:1 — on a light page the "luminous" fill sat *lighter* than
 * the AA-fitted text role while the track was a pale tint of the same hue, so luminance
 * separation collapsed to hue alone. The light fills are now authored against the track
 * rather than against the page; `DesignSystemTest.progressFillSeparatesFromItsTrack` pins
 * it.)
 *
 * A caller that brings its own fill — a cover accent, or a caption's own colour — keeps its
 * own faded track, so the seam always belongs to one family.
 */
@Composable
fun FolioProgressBar(
    progress: Float,
    modifier: Modifier = Modifier,
    color: Color = FolioTheme.colors.progress,
    track: Color? = null,
) {
    val colors = FolioTheme.colors
    val motion = com.folio.reader.ui.theme.rememberMotionEnabled()
    val fraction by animateFloatAsState(
        targetValue = progress.coerceIn(0f, 1f),
        animationSpec = if (motion) tween(FolioTokens.motionStandard.toInt()) else snap(),
        label = "folioProgress",
    )
    // The authored track belongs to the authored fill; a caller's own accent keeps the
    // derived one, so the two can never come from different families.
    val trackColor = track
        ?: if (color == colors.progress) colors.progressTrack else color.copy(alpha = 0.14f)
    Box(
        modifier = modifier
            .fillMaxWidth()
            .progressSemantics(fraction)
            .height(3.dp)
            .background(trackColor, RoundedCornerShape(2.dp))
    ) {
        Box(
            modifier = Modifier
                .fillMaxWidth(fraction)
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
    // §16 the in-window overlay layer: the pane is placed from the trigger's bounds, so the well
    // the reader tapped is what the menu hangs off (see FolioMenu).
    var menuAnchor by remember { mutableStateOf<Rect?>(null) }

    androidx.compose.foundation.layout.BoxWithConstraints(modifier = modifier.fillMaxWidth()) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .onGloballyPositioned { menuAnchor = it.boundsInWindow() }
                .clickable { expanded = !expanded }
                // An input is a **well**, not a slab — that is the material table's own
                // ruling (`folioSunken`: "wells: charts, heatmaps, inputs"). This row was a
                // `glassPanel`, so inside the settings shell's glass it was an opaque,
                // hard-rimmed card sitting on a card: the single most-repeated instance of
                // the nested-box texture, seven call sites across Reader defaults and
                // Text & page. The well inverts its lighting instead of adding a rim, so it
                // reads as recessed into the panel at any depth and carries no border to
                // duplicate the panel's own.
                .folioSunken(RoundedCornerShape(12.dp))
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

        // §16 P5: the pane is now the app's own glass in the app's own window (see FolioMenu /
        // FolioOverlayHost), placed from the trigger's bounds. It used to be Material's
        // `DropdownMenu`, which renders in a *separate window* — and there the backdrop registry
        // does not exist, so the best it could ever be is its fill. It now blurs the room and the
        // page like every other glass surface.
        //
        // The history is worth keeping, because it is why the material looked wrong for so long:
        // before the ladder, this site hand-rolled a `surface.copy(alpha = 0.97f)` — an
        // approximation of `veilFill` that missed its lift and its chroma entirely and therefore
        // rendered near-black on every dark face — plus a `glassPanel` and a `border` *both*
        // stroking a hairline, on a node the menu draws its own plate under. Three plates and two
        // concentric rims for one menu.
        FolioMenu(
            expanded = expanded,
            onDismissRequest = { expanded = false },
            anchor = menuAnchor,
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
            // §20: with minimal liquid glass off, the stat figures join the section
            // cards in real glass — a well is a chart's bed, but a lone figure sitting
            // in one read as a dull painted tile next to live glass. The gate keeps the
            // shipped well when the user opted into minimal glass or the device cannot
            // blur, so nothing regresses there.
            .then(
                if (com.folio.reader.ui.components.inFlowGlassActive()) {
                    Modifier.folioVeil(
                        FolioShapes.inset,
                        accent = color,
                        tier = com.folio.reader.ui.theme.FolioGlassTier.InFlow,
                    )
                } else {
                    Modifier.folioSunken(FolioShapes.inset, accent = color)
                }
            )
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
    // A destructive confirm gets the sharp cautionary double; an ordinary one the
    // single confirm click — the "it took" feel from the Living Paper vocabulary.
    val haptics = com.folio.reader.ui.theme.rememberFolioHaptics()
    // §16 P5: the app's own in-window pane, not Material's `AlertDialog`. Same reason as every
    // other overlay — a `Dialog` is a second window, so the backdrop registry does not exist in it
    // and it can never blur the room behind it. `expanded = true` because this composable *is* the
    // dialog: a caller composes it only when it should be up, and FolioDialog closes the pane when
    // it leaves the composition.
    FolioDialog(
        expanded = true,
        onDismissRequest = onDismiss,
        title = title,
    ) {
        Text(text = message, style = MaterialTheme.typography.bodyMedium)
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(top = FolioTokens.space3),
            horizontalArrangement = Arrangement.End,
            verticalAlignment = Alignment.CenterVertically,
        ) {
            TextButton(onClick = onDismiss) {
                Text(dismissText)
            }
            Spacer(Modifier.width(FolioTokens.space2))
            Button(
                onClick = {
                    haptics.play(
                        if (destructive) com.folio.reader.ui.theme.FolioHaptic.Reject
                        else com.folio.reader.ui.theme.FolioHaptic.Confirm
                    )
                    onConfirm(); onDismiss()
                },
                colors = androidx.compose.material3.ButtonDefaults.buttonColors(
                    containerColor = if (destructive) com.folio.reader.ui.theme.FolioTheme.colors.error
                    else com.folio.reader.ui.theme.FolioTheme.colors.primary,
                    contentColor = if (destructive) com.folio.reader.ui.theme.FolioTheme.colors.onError
                    else com.folio.reader.ui.theme.FolioTheme.colors.onPrimary
                )
            ) {
                Text(confirmText)
            }
        }
    }
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
 *
 * §16: it floats over the capsule and the page, so it gets the veil treatment —
 * blur under its fill when a backdrop source is live, grain and a hairline
 * otherwise, and always its own message-kind container colour.
 */
@Composable
fun FolioStatusBanner(message: String, modifier: Modifier = Modifier) {
    val colors = com.folio.reader.ui.theme.FolioTheme.colors
    val inProgress = isStatusInProgress(message)
    val isError = isStatusError(message)
    val container = if (isError) colors.errorContainer else colors.inverseSurface
    val content = if (isError) colors.onErrorContainer else colors.inverseOnSurface
    val shape = RoundedCornerShape(com.folio.reader.ui.theme.FolioTokens.radiusControl)
    val caps = LocalGlassCapabilities.current
    val backdrop = LocalGlassBackdrop.current
    val canBlur = caps.blur && backdrop != null
    val bannerSpec = rememberMotionAnimation<Float>(MotionKey.StatusBanner)
    val bannerSlide = rememberMotionAnimation<IntOffset>(MotionKey.StatusBanner)

    androidx.compose.animation.AnimatedVisibility(
        visible = message.isNotBlank(),
        // Four literals (180/220 in, 160/200 out) collapsed to the banner's own row: the banner is
        // one object arriving and leaving, not two animations that happen to share a surface.
        enter = androidx.compose.animation.fadeIn(bannerSpec) +
            androidx.compose.animation.slideInVertically(animationSpec = bannerSlide) { it / 2 },
        exit = androidx.compose.animation.fadeOut(bannerSpec) +
            androidx.compose.animation.slideOutVertically(animationSpec = bannerSlide) { it / 2 },
        modifier = modifier
    ) {
        Row(
            modifier = Modifier
                .widthIn(max = 420.dp)
                .shadow(10.dp, shape)
                .then(
                    if (canBlur) {
                        Modifier
                            .clip(shape)
                            .folioGlassEffect(
                                backdrop = backdrop,
                                backgroundColor = colors.background,
                                // §16 cost model: a transient toast-width banner is small,
                                // high-attention chrome. See FolioGlassCost.
                                inputScale = FolioGlassCost.resolve(
                                    FolioGlassCost.control,
                                    caps,
                                ),
                            )
                    } else {
                        Modifier
                    }
                )
                .background(container, shape)
                .then(if (caps.noise && !canBlur) Modifier.folioGlassGrain(shape) else Modifier)
                .border(
                    1.dp,
                    com.folio.reader.ui.theme.FolioTheme.atmosphere.hairline,
                    shape,
                )
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
