package com.folio.reader.ui.components

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ColumnScope
import androidx.compose.foundation.layout.IntrinsicSize
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxHeight
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.text.style.TextOverflow
import com.folio.reader.ui.theme.folioGuillocheQuiet
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.folio.reader.ui.theme.FolioAtmosphere
import com.folio.reader.ui.theme.FolioAmbientTint
import com.folio.reader.ui.theme.FolioShapes
import com.folio.reader.ui.theme.FolioTheme
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.LocalFolioAmbientTint
import com.folio.reader.ui.theme.atmosphere
import com.folio.reader.ui.theme.barGlassFor
import com.folio.reader.ui.theme.fieldColors
import com.folio.reader.ui.theme.navGlassFor
import com.folio.reader.ui.theme.rememberFolioAmbientColor

/**
 * Typography as structure.
 *
 * The previous build put a `titleSmall` in `primary` at the top of every card and
 * called that hierarchy — so every section had the same voice at the same volume.
 * These primitives give sections real typographic rank:
 *
 *  - [FolioEyebrow] — a small, letterspaced, accent-coloured kicker *outside* any
 *    container. It labels a region of the page rather than titling a box.
 *  - [FolioSectionHead] — the display-face section heading, optionally with a
 *    trailing action. The loudest thing on a non-hero screen.
 *  - [FolioFigure] — a large numeral treated as an editorial figure: the number in
 *    the display face, its unit riding small alongside, caption beneath.
 *  - [FolioCallout] — a pull-quote block: a rule on the leading edge, no box.
 */

/**
 * Secondary ink for text that floats on the **bare field** — eyebrows, figure captions,
 * empty-state prose — none of which sit on a card. The field is now a colour-rich mesh
 * gradient (see folioMeshField), and the quiet slate `onSurfaceVariant` that reads as a
 * proper secondary tier *on a card* recedes into "dull" against that richer ground. This
 * lifts it partway toward `onSurface` so it holds its weight on the field while staying
 * clearly below a full `onSurface` headline. Card-hosted `onSurfaceVariant` is untouched —
 * only the bare-field primitives route through here.
 */
@Composable
internal fun rememberFieldSecondaryInk(): Color {
    val colors = FolioTheme.colors
    return remember(colors.onSurfaceVariant, colors.onSurface) {
        androidx.compose.ui.graphics.lerp(colors.onSurfaceVariant, colors.onSurface, 0.42f)
    }
}

/** Small letterspaced kicker. Labels a region; never sits inside a card. */
@Composable
fun FolioEyebrow(
    text: String,
    modifier: Modifier = Modifier,
    accent: Color? = null,
    /**
     * The ground the kicker is read on. The page's own surface by default; a
     * caller that labels a panel or a hero passes that plane instead, so the
     * guard below measures the contrast the reader actually gets.
     */
    background: Color = FolioTheme.colors.surface,
) {
    // The kicker floats on the bare field, so its ink is the lifted field-secondary tier
    // (see rememberFieldSecondaryInk), not the card-tier `onSurfaceVariant` that recedes
    // against the richer mesh field. A passed accent still guards against this ink.
    val fieldInk = rememberFieldSecondaryInk()
    // An accent may name a region; it may not make the name unreadable. Every
    // eyebrow is uppercase `labelSmall` with 1.4sp of tracking — small type by
    // WCAG's own line, which only lets a run near 24sp (or 18.66sp bold) have
    // the 3:1 large-text tier — so this guards at 4.5:1 and blends toward the
    // field-secondary ink the kicker already falls back to, rather than the
    // louder `onSurface`. A null accent is that fallback by definition.
    val ink = remember(accent, background, fieldInk) {
        if (accent == null) {
            fieldInk
        } else {
            legibleOn(accent, background, fieldInk, 4.5)
        }
    }
    Text(
        text = text.uppercase(),
        style = FolioTheme.typography.labelSmall,
        letterSpacing = 1.4.sp,
        color = ink,
        maxLines = 1,
        overflow = TextOverflow.Ellipsis,
        // The guilloche plate stands down behind this label. A kicker's own doc says it
        // "never sits inside a card", so it is usually on bare field — and a thin line
        // pattern behind thin uppercase tracking at 1.4sp is exactly where ragging shows
        // worst. Declaring it here rather than at 25 call sites means a new label cannot
        // be added without the protection. On the rare panel-hosted kicker this is a
        // harmless no-op: the panel is opaque and the plate is already invisible there.
        modifier = modifier.folioGuillocheQuiet(),
    )
}

/**
 * Section label inside a menu, so one menu can carry two or three groups and still
 * be read at a glance. A twenty-item flat list is not a menu, it is an inventory.
 *
 * [FolioEyebrow]'s quieter sibling: the same uppercase kicker at the same type rank,
 * but inset to a menu's own gutters and never in an accent, because a menu group is
 * structure and structure does not get to shout.
 */
@Composable
fun FolioMenuLabel(text: String, modifier: Modifier = Modifier) {
    Text(
        text = text.uppercase(),
        style = FolioTheme.typography.labelSmall,
        // Uppercase small text needs tracking to read as a kicker, like every
        // other eyebrow in the app (FolioEyebrow adds 1.4sp).
        letterSpacing = 1.2.sp,
        color = FolioTheme.colors.onSurfaceVariant,
        modifier = modifier.padding(start = 14.dp, end = 14.dp, top = 10.dp, bottom = 4.dp),
    )
}

/**
 * A section heading in the display face, with an optional trailing affordance.
 * [eyebrow] rides above it when the section needs a category as well as a name.
 */
@Composable
fun FolioSectionHead(
    title: String,
    modifier: Modifier = Modifier,
    eyebrow: String? = null,
    accent: Color? = null,
    trailing: (@Composable () -> Unit)? = null,
) {
    Row(
        // The loudest thing on a non-hero screen — "Continue reading", "Where your time
        // went" — and it sits on bare field. Same standing-down as the eyebrow and the
        // figure, declared here so every section head in the app is covered at once.
        modifier = modifier.fillMaxWidth().folioGuillocheQuiet(),
        verticalAlignment = Alignment.Bottom,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            if (eyebrow != null) {
                FolioEyebrow(eyebrow, accent = accent ?: FolioTheme.colors.accentDiscovery)
                Spacer(Modifier.height(3.dp))
            }
            Text(
                text = title,
                style = FolioTheme.typography.headlineSmall,
                color = FolioTheme.colors.onSurface,
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
        if (trailing != null) {
            Spacer(Modifier.width(FolioTokens.space2))
            trailing()
        }
    }
}

/**
 * A number treated as a figure, not a label. The value carries the display face at
 * figure scale; the unit rides small on the baseline beside it, so "9" and "m" are
 * not the same size; the caption sits beneath.
 *
 * [emphasis] scales the whole figure: the one number a screen is about goes to
 * [FigureScale.Hero], supporting numbers stay [FigureScale.Standard], and the long
 * tail is [FigureScale.Quiet]. That spread *is* the hierarchy.
 */
enum class FigureScale { Hero, Standard, Quiet }

@Composable
fun FolioFigure(
    value: String,
    modifier: Modifier = Modifier,
    unit: String? = null,
    label: String? = null,
    caption: String? = null,
    accent: Color? = null,
    emphasis: FigureScale = FigureScale.Standard,
) {
    val tint = accent ?: FolioTheme.colors.onSurface
    val valueStyle = when (emphasis) {
        FigureScale.Hero -> FolioTheme.typography.displayMedium
        FigureScale.Standard -> FolioTheme.typography.displaySmall
        FigureScale.Quiet -> FolioTheme.typography.headlineSmall
    }
    // A figure is a large numeral read at a glance, and on Stats it sits on bare field:
    // "Read today 67%", "STREAK 1 day", "THIS YEAR". The plate stands down behind the
    // whole figure — numeral, unit and caption together — for the same reason as the
    // eyebrow above, and declared once here so every figure in the app is covered.
    Column(modifier = modifier.folioGuillocheQuiet()) {
        if (label != null) {
            // null accent → FolioEyebrow uses the lifted field-secondary ink (bare field).
            FolioEyebrow(label)
            Spacer(Modifier.height(if (emphasis == FigureScale.Hero) 6.dp else 3.dp))
        }
        Row(verticalAlignment = Alignment.Bottom) {
            Text(
                text = value,
                // Tabular figures: the number is the point of a figure, and it must
                // not re-width as digits change (count-ups, live streak/minutes).
                style = valueStyle.copy(fontFeatureSettings = "tnum"),
                color = tint,
                maxLines = 1,
            )
            if (unit != null) {
                Spacer(Modifier.width(3.dp))
                Text(
                    text = unit,
                    style = FolioTheme.typography.titleSmall,
                    color = tint.copy(alpha = 0.70f),
                    maxLines = 1,
                    modifier = Modifier.padding(
                        bottom = if (emphasis == FigureScale.Hero) 6.dp else 3.dp,
                    ),
                )
            }
        }
        if (caption != null) {
            Spacer(Modifier.height(5.dp))
            Text(
                text = caption,
                style = FolioTheme.typography.bodySmall,
                color = rememberFieldSecondaryInk(),
                maxLines = 2,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/**
 * Pull-quote block. A 2dp accent rule on the leading edge instead of a card:
 * quotes are the author's voice, and boxing them makes them read as UI.
 *
 * `height(IntrinsicSize.Min)` on the row is what lets the rule match the text it
 * annotates — `fillMaxHeight` alone inside a wrap-content parent measures to zero.
 */
@Composable
fun FolioCallout(
    modifier: Modifier = Modifier,
    accent: Color? = null,
    content: @Composable ColumnScope.() -> Unit,
) {
    val rule = accent ?: FolioTheme.colors.accentAnnotation
    Row(modifier = modifier.fillMaxWidth().height(IntrinsicSize.Min)) {
        Box(
            Modifier
                .width(2.dp)
                .fillMaxHeight()
                .background(rule.copy(alpha = 0.65f), FolioShapes.plateSmall),
        )
        Spacer(Modifier.width(FolioTokens.space2))
        Column(
            modifier = Modifier.weight(1f),
            verticalArrangement = Arrangement.spacedBy(4.dp),
            content = content,
        )
    }
}

/**
 * A place's own colour, keyed by the strings the app already uses to name it.
 *
 * Chrome is byte-identical across screens — one `primary` at one alpha on every
 * masthead and every capsule — which is most of why four different rooms read as
 * one room. The palette already carries four *semantic* accents, and the app uses
 * them consistently (§12.3): progress for what is being read, discovery for what
 * may be found, streak for what was done, annotation for what was marked. This is
 * the single table that spends them on destinations, keyed on a route id
 * ("library", "stats") or a segmented label ("Books", "Manga") so the nav capsule
 * and the masthead's own segment switch agree by construction rather than by two
 * hardcoded lists drifting.
 *
 * [fallback] is the theme's signature `primary`, so any key not in the table — the
 * reader's font-size switch, a theme card row's layout choice — keeps exactly the
 * colour it wore before, and only real destinations change.
 */
@Composable
fun folioDestinationAccent(
    key: String,
    fallback: Color = FolioTheme.colors.primary,
): Color {
    val colors = FolioTheme.colors
    return when (key.trim().lowercase()) {
        "home", "books" -> colors.accentProgress
        "library", "manga" -> colors.accentDiscovery
        "stats", "documents" -> colors.accentStreak
        "more", "settings" -> colors.accentAnnotation
        else -> fallback
    }
}

/**
 * The glass a bar is made of *in the room the reader is standing in*.
 *
 * [FolioAtmosphere.barGlass] is derived once per palette while the colour of the
 * book being read is a runtime value, so a bar cannot bake it in at palette time — it
 * asks [barGlassFor] here, at the atmosphere's own
 * [FolioAtmosphere.fieldTintStrength] rather than a second constant.
 *
 * The holder is read for its **nullable** `requested`, exactly as [folioFieldBottom]
 * and both `scrimFor` call sites do, rather than through
 * [rememberFolioAmbientColor] with the glass as the fallback. That fallback encoded
 * "nothing lit" as "lit by the glass itself", which was invisible while the bar mixed
 * toward a raw colour but made it impossible to aim the bar at the *lit field* — an
 * unlit tree would then have arrived lit by its own glass. Null now means unlit, so
 * desktop, every preview and every unit test still get [FolioAtmosphere.barGlass]
 * byte-for-byte, and a bar is finally free to follow the room's lightness and not
 * merely its hue.
 */
@Composable
fun folioBarGlass(atmos: FolioAtmosphere = FolioTheme.atmosphere): Color =
    barGlassFor(atmos, LocalFolioAmbientTint.current?.requested, atmos.fieldTintStrength)

/**
 * The glass the **nav capsule** is made of in this room — the capsule's counterpart to
 * [folioBarGlass], and deliberately not the same colour.
 *
 * The capsule used to ask [folioBarGlass] for its room wash, which is how the one surface
 * with a plane of its own ended up wearing the top bar's hue: the wash is applied through
 * `BlendMode.Color`, so it replaces hue *and* chroma and keeps only the capsule's luminance.
 * Two pieces of chrome at opposite ends of the window are two objects, so they ask two
 * questions, and this one is keyed on [FolioAtmosphere.navigationSurface].
 *
 * Same contract as [folioBarGlass]: `requested` is read **nullable**, so an unlit tree —
 * desktop, every preview, every unit test — gets [FolioAtmosphere.navigationSurface]
 * byte-for-byte. That exact identity is what lets a caller test "is this room lit?" by
 * comparing against the plane, which is the test the capsule's wash needs and the one it
 * could not get while the chrome accent's pull lived inside `barGlassFor` (the result never
 * equalled `barGlass`, so the capsule's skip branch was dead and the wash fired on every
 * unlit screen).
 */
@Composable
fun folioNavGlass(atmos: FolioAtmosphere = FolioTheme.atmosphere): Color =
    navGlassFor(atmos, LocalFolioAmbientTint.current?.requested, atmos.fieldTintStrength)

/**
 * The field's lower endpoint as the room lights it — the colour anything that
 * *dissolves into* the ground plane must wear, or it dissolves into a colour the
 * page is not.
 *
 * Deliberately not [rememberFolioAmbientColor]: [fieldColors] treats a null tint as
 * "the palette's own", and no fallback colour can say that here, because a lit room
 * also *deepens* its lower endpoint. So the holder is read for the same nullable
 * value the helper itself reads — [FolioAmbientTint.requested], not the crossfading
 * `shown`, so a chrome surface recomposes once per book and not once per frame.
 */
@Composable
fun folioFieldBottom(atmos: FolioAtmosphere = FolioTheme.atmosphere): Color {
    val tint = LocalFolioAmbientTint.current?.requested
    return fieldColors(atmos, tint, atmos.fieldTintStrength).bottom
}
