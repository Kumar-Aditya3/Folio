package com.folio.reader.ui.theme

import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.remember
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import kotlin.math.roundToInt

/**
 * Within-class shape families: many heros, one silhouette each.
 *
 * [FolioShapes] fixed the *vocabulary* — one silhouette per class of object. This file
 * adds the *grammar*: how an individual hero, card or inset may differ from its siblings
 * without ever being mistaken for another class. A pure vocabulary is what makes a shelf
 * of twelve books twelve identical rectangles, and repetition at that scale is what reads
 * as a template. Variation is the ask; the doctrine is what has to survive getting it.
 *
 * **What may vary, and what may not.** Four classes are free ([FolioShapeClass.HERO],
 * [FolioShapeClass.CARD], [FolioShapeClass.INSET], [FolioShapeClass.CALLOUT]) because
 * their identity lives in a *relationship between* corners — wide against narrow, round
 * against flat — which keeps holding while the individual values move. Four are pinned
 * ([FolioShapeClass.PLATE], [FolioShapeClass.CONTROL], [FolioShapeClass.SHEET],
 * [FolioShapeClass.NAV]) because their identity *is* their value: a plate that wanders
 * toward 8dp stops being paper and becomes a widget, and the moment a pill can be any
 * radius, "pill ⇒ tappable" is no longer readable. Pinning is written as a zero budget
 * rather than as a flag, so the table has one mechanism ([FolioShapeRow]).
 *
 * **Seeds.** A silhouette belongs to the *thing*, not to where it happens to sit: seed
 * from a stable identity (item id, `coverPath.hashCode()`) exactly as `BookCover.kt:276`
 * picks its fallback gradient (`abs(title.hashCode()) % n`), never from a shelf index —
 * otherwise the shelf re-draws its whole geometry on every sort and nothing is a stable
 * object any more.
 *
 * **Cost.** [folioShapeFor] is arithmetic over whole-dp values, and it returns the row's
 * shipped [FolioShapes] instance whenever the solve lands on the base, so every pinned
 * class and every switched-off build costs zero allocations. [rememberFolioShape] exists
 * because a `Shape` is only cheap if it is *the same* cheap object:
 * `RoundedCornerShape.createOutline` rebuilds a RoundRect/Path on every read, so instance
 * stability at composition is what keeps this at one shape per item and zero per frame.
 *
 * ## Motion, and the rule call sites are held to
 *
 * The animated forms ([folioShapeForming], [folioShapeMorph]) take a `fraction: Float`
 * and return a `Shape`, so a caller already holding a `State<Float>` — a masthead's
 * `collapse`, an entry progress, a press depth — can drive one from a draw or layout
 * phase. They are deliberately not `@Composable`, and they read no environment: no
 * `Transition`, no `Animatable`, no `LocalFolioAmbient`. A shape family that recomposed
 * its holders, or that took its geometry from the room, would be a second lighting model
 * in a codebase that has already spent one § on not having two. Reduce-motion is the
 * caller's `rememberMotionEnabled()`, handed in as `motion = false`, which collapses the
 * ramp onto the static family value instead of inventing a third one.
 *
 * **The rule (§12.1, enforced at every call site):** a shape passed to `background`,
 * `clip` or `shadow` on an *in-flow* box must never animate. Radius is content-affecting
 * geometry there — the inner bounds move, so the children relayout, so one breathing
 * corner costs the subtree a measure pass per frame and reads as jitter rather than as
 * life. Only a *decorative* path, painted inside `drawWithCache` onto the outline the
 * surface is already clipped to, may breathe: that is what
 * `components/FolioSurfaces.kt`'s `CacheDrawScope.shapePath` exists for, and the animated
 * forms below are written to feed it and nothing else. In-flow boxes get the static value
 * from [rememberFolioShape] and hold it.
 */
enum class FolioShapeClass {
    /**
     * The one hero per screen: 34/20/34/8. FREE — both wide corners travel ±6dp.
     *
     * Free because the hero reads as a hero from the *gap* between its wide pair and its
     * narrow pair, not from the pair's values: 40/17/32/4 and 28/20/30/12 are both
     * unmistakably heroes. The gap is not left to the dice — see [FolioShapeFamily.heroGuard].
     */
    HERO,

    /** Grouped content, 18dp. FREE ±3dp, which is the largest budget that keeps every card clear of inset's ceiling. */
    CARD,

    /** Sunken panels, 12dp. FREE ±2dp — the tightest budget, because a well is identified by its depth against the plane around it. */
    INSET,

    /**
     * Quote blocks, 2/14/14/2. FREE, but unevenly: the trailing pair travels ±4dp and the
     * leading pair only ±1dp, because "flat leading edge for the rule" *is* the silhouette,
     * and 3dp still reads as flat where 8dp does not.
     */
    CALLOUT,

    /** Printed objects — [FolioShapes.plate], and [FolioShapes.plateSmall] at list scale. PINNED: a cover's radius is content, not chrome. */
    PLATE,

    /** Pills and chips. PINNED: an affordance vocabulary cannot be relative, or it stops being read as one. */
    CONTROL,

    /** Window-anchored sheets. PINNED: the 28dp shoulder is what announces that a sheet can be dragged. */
    SHEET,

    /** The nav capsule. PINNED: 26dp is half the bar's height — move it and the capsule is either a pill or a rectangle. */
    NAV,
}

/**
 * Four corner radii, in the order [RoundedCornerShape] takes them: the unit the family
 * table is written in, and the unit the invariants are asserted in. A `data class` so the
 * tests can count distinct silhouettes as values.
 */
@Immutable
data class FolioCorners(
    val topStart: Dp,
    val topEnd: Dp,
    val bottomEnd: Dp,
    val bottomStart: Dp,
) {
    /** True when every corner sits exactly on [other] — i.e. this is the base silhouette. */
    fun sameShapeAs(other: FolioCorners): Boolean =
        topStart == other.topStart && topEnd == other.topEnd &&
            bottomEnd == other.bottomEnd && bottomStart == other.bottomStart

    /** Corner by index: 0 topStart, 1 topEnd, 2 bottomEnd, 3 bottomStart. */
    internal operator fun get(corner: Int): Dp = when (corner) {
        0 -> topStart
        1 -> topEnd
        2 -> bottomEnd
        else -> bottomStart
    }

    internal fun lerpTo(to: FolioCorners, f: Float): FolioCorners = FolioCorners(
        topStart = topStart + (to.topStart - topStart) * f,
        topEnd = topEnd + (to.topEnd - topEnd) * f,
        bottomEnd = bottomEnd + (to.bottomEnd - bottomEnd) * f,
        bottomStart = bottomStart + (to.bottomStart - bottomStart) * f,
    )

    /**
     * Not `equals`, on purpose: a `RoundedCornerShape` does not implement structural
     * equality, so the identity guarantee has to be taken from the corners and then
     * handed back as the shipped instance rather than as a look-alike.
     */
    internal fun toShape(): Shape = RoundedCornerShape(topStart, topEnd, bottomEnd, bottomStart)

    internal companion object {
        fun of(corner: (Int) -> Dp): FolioCorners = FolioCorners(corner(0), corner(1), corner(2), corner(3))
    }
}

/**
 * Which corners a row may move at all. Kept distinct from [FolioShapeRow.delta] because
 * they answer different questions: the delta says *how far* a corner may travel, the mask
 * says whether travel is permitted for a structural reason no budget can buy back. A bled
 * hero's leading corners are the case — `0.dp` there is not a small radius, it is the
 * absence of an edge, and opening a radius lets the page be seen through the cover that is
 * supposed to run off the screen.
 */
@Immutable
data class FolioCornerMask(
    val topStart: Boolean,
    val topEnd: Boolean,
    val bottomEnd: Boolean,
    val bottomStart: Boolean,
) {
    operator fun get(corner: Int): Boolean = when (corner) {
        0 -> topStart
        1 -> topEnd
        2 -> bottomEnd
        else -> bottomStart
    }

    companion object {
        val All = FolioCornerMask(true, true, true, true)
        val None = FolioCornerMask(false, false, false, false)

        /** Only the trailing pair may move: the leading edge meets something structural. */
        val Trailing = FolioCornerMask(topStart = false, topEnd = true, bottomEnd = true, bottomStart = false)

        internal fun of(movable: (Int) -> Boolean): FolioCornerMask =
            FolioCornerMask(movable(0), movable(1), movable(2), movable(3))
    }
}

/**
 * One row of the family table: a silhouette, the budget each corner may spend, which
 * corners may spend it, and the [FolioShapes] instance it must return when nothing moved.
 *
 * Rows rather than classes are the unit of the API, which is what lets the vocabulary's
 * *variants* — `heroBleed`, `plateSmall`, `chip`, `edgeStart` — carry the identical
 * guarantees without inventing enum entries for silhouettes that are not separate classes
 * of object. Pinned ones (plateSmall, chip) stay reachable as `FolioShapes` values by
 * name, exactly as they are today; the rows exist so that the invariant "a pinned class
 * returns the shipped instance" is one table's property rather than eight call-site habits.
 */
@Immutable
class FolioShapeRow(
    val base: FolioCorners,
    val delta: FolioCorners,
    val mask: FolioCornerMask,
    /** Per-row salt. Unsalted, a hero and a card seeded from the same book would roll the same corner pattern and the classes would rhyme. */
    val salt: Int,
    /**
     * The least this row's wide pair must clear its narrow pair by, or `0.dp` for rows
     * that declare no such relationship. Enforced inside [folioCornersFor] by clamping,
     * so it is an output invariant rather than a hope about the table.
     */
    val guard: Dp = 0.dp,
    /** The shipped vocabulary value this row was derived from; returned untouched whenever the solve lands on the base. */
    val baseShape: Shape,
) {
    /** True when no corner can move: the row *is* its [baseShape]. */
    val pinned: Boolean = delta.topStart == 0.dp && delta.topEnd == 0.dp &&
        delta.bottomEnd == 0.dp && delta.bottomStart == 0.dp

    /**
     * The two roundest corners of [base] — the pair a [guard] measures against. Derived
     * from the base so each silhouette is still stated once in the table; only defined for
     * rows that already have a wide/narrow split, which is the whole point of a guard.
     */
    val widePair: FolioCornerMask = if (guard == 0.dp) {
        FolioCornerMask.None
    } else {
        val roundest = (0..3).sortedByDescending { base[it] }
        FolioCornerMask.of { it == roundest[0] || it == roundest[1] }
    }
}

/** The ceiling on any solved corner. Half of [FolioShapes.nav]'s widest case would be a disc, and nothing shipped is that round. */
internal val FolioMaxCorner: Dp = 50.dp

/**
 * The family table: the [FolioShapes] vocabulary, plus a per-corner budget. No value here
 * is a dice roll, and every budget is small enough that the classes' reachable ranges stay
 * disjoint, which is what keeps Rule 13's three tiers three tiers once variation is on:
 *
 *  - hero — wide 28..40, narrow 4..23, and never within 8dp of the wide pair's tighter
 *    corner whatever the dice roll (the guard, not the range, is what holds them apart);
 *  - card 15..21, inset 10..14 — card's floor clears inset's ceiling, so "embedded things
 *    read smaller than the surface holding them" survives every seed;
 *  - callout — leading 1..3, trailing 10..18, so the rule edge stays flat at any seed.
 *
 * The pinned rows are in here with zero budgets rather than omitted, so that
 * "pinned ⇒ the shipped instance" is a property of the table that the tests can walk.
 */
object FolioShapeFamily {
    /** Rule 13's blur test, as a number: how far the hero's wide pair must stay above its narrow pair. */
    val heroGuard: Dp = 8.dp

    val hero = FolioShapeRow(
        base = FolioCorners(34.dp, 20.dp, 34.dp, 8.dp),
        delta = FolioCorners(6.dp, 3.dp, 6.dp, 4.dp),
        mask = FolioCornerMask.All,
        salt = 0x11D1,
        guard = heroGuard,
        baseShape = FolioShapes.hero,
    )

    /**
     * The bled hero: free on the trailing pair, structurally pinned on the leading one.
     * Reachable only as a row, since it is a variant of a hero rather than a class of object.
     */
    val heroBleed = FolioShapeRow(
        base = FolioCorners(0.dp, 26.dp, 26.dp, 0.dp),
        delta = FolioCorners(0.dp, 4.dp, 4.dp, 0.dp),
        mask = FolioCornerMask.Trailing,
        salt = 0x11D2,
        baseShape = FolioShapes.heroBleed,
    )

    val card = FolioShapeRow(
        base = FolioCorners(18.dp, 18.dp, 18.dp, 18.dp),
        delta = FolioCorners(3.dp, 3.dp, 3.dp, 3.dp),
        mask = FolioCornerMask.All,
        salt = 0x11D3,
        baseShape = FolioShapes.card,
    )

    val inset = FolioShapeRow(
        base = FolioCorners(12.dp, 12.dp, 12.dp, 12.dp),
        delta = FolioCorners(2.dp, 2.dp, 2.dp, 2.dp),
        mask = FolioCornerMask.All,
        salt = 0x11D4,
        baseShape = FolioShapes.inset,
    )

    val callout = FolioShapeRow(
        base = FolioCorners(2.dp, 14.dp, 14.dp, 2.dp),
        delta = FolioCorners(1.dp, 4.dp, 4.dp, 1.dp),
        mask = FolioCornerMask.All,
        salt = 0x11D5,
        baseShape = FolioShapes.callout,
    )

    /** A printed object. Pinned, and the pin is the doctrine: 3dp is paper, 12dp is a widget. */
    val plate = FolioShapeRow(
        base = FolioCorners(2.dp, 5.dp, 5.dp, 2.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11D6,
        baseShape = FolioShapes.plate,
    )

    /** The same pin at list scale. No class key, because it is a plate at another size and not a second class of object. */
    val plateSmall = FolioShapeRow(
        base = FolioCorners(1.dp, 3.dp, 3.dp, 1.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11D7,
        baseShape = FolioShapes.plateSmall,
    )

    /**
     * Pinned. `CircleShape` has no corner representation whatsoever — its radius is half
     * of whichever box clips it — which is the cleanest possible statement of why controls
     * are outside the family: a pill's silhouette is not a number this file could vary.
     * [FolioShapeRow.base] is the honest zero vector and is never read, because the pin
     * short-circuits ahead of the solver.
     */
    val pill = FolioShapeRow(
        base = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11D8,
        baseShape = FolioShapes.pill,
    )

    /** Pinned: a chip is a control that must not read as a button, so its 9dp is a floor on meaning. */
    val chip = FolioShapeRow(
        base = FolioCorners(9.dp, 9.dp, 9.dp, 9.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11D9,
        baseShape = FolioShapes.chip,
    )

    /**
     * Full-width sections that meet the screen edge on the leading side. The leading pair
     * is structural for the same reason as [heroBleed]'s; the trailing pair is free,
     * because out there it is only a corner. Also the collapsed end of a hero's morph.
     */
    val edgeStart = FolioShapeRow(
        base = FolioCorners(0.dp, 20.dp, 20.dp, 0.dp),
        delta = FolioCorners(0.dp, 3.dp, 3.dp, 0.dp),
        mask = FolioCornerMask.Trailing,
        salt = 0x11DA,
        baseShape = FolioShapes.edgeStart,
    )

    /** Pinned: a sheet is anchored to the window, so its shoulders are architecture. */
    val sheet = FolioShapeRow(
        base = FolioCorners(28.dp, 28.dp, 0.dp, 0.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11DB,
        baseShape = FolioShapes.sheet,
    )

    val nav = FolioShapeRow(
        base = FolioCorners(26.dp, 26.dp, 26.dp, 26.dp),
        delta = FolioCorners(0.dp, 0.dp, 0.dp, 0.dp),
        mask = FolioCornerMask.None,
        salt = 0x11DC,
        baseShape = FolioShapes.nav,
    )

    /** The class-keyed view of the table. Variants are reached by name ([heroBleed], [plateSmall], [chip], [edgeStart]). */
    operator fun get(klass: FolioShapeClass): FolioShapeRow = when (klass) {
        FolioShapeClass.HERO -> hero
        FolioShapeClass.CARD -> card
        FolioShapeClass.INSET -> inset
        FolioShapeClass.CALLOUT -> callout
        FolioShapeClass.PLATE -> plate
        FolioShapeClass.CONTROL -> pill
        FolioShapeClass.SHEET -> sheet
        FolioShapeClass.NAV -> nav
    }

    /** Derived from the budgets, so the table stays the only place the free/pinned split is written. */
    val free: Set<FolioShapeClass> = FolioShapeClass.entries.filterNot { get(it).pinned }.toSet()
}

/** Whether this class is outside the grammar entirely. Read from its row, never declared twice. */
val FolioShapeClass.pinned: Boolean get() = FolioShapeFamily[this].pinned

/**
 * The solved corner set for one object — the single place the grammar is applied, and
 * `internal` so the invariants can be asserted on numbers rather than on opaque [Shape]s
 * (a `RoundedCornerShape` will not hand back the corners it was built from).
 *
 * Order matters: budget first (a masked corner never moves whatever its delta claims),
 * then the guard, then the clamp to `0..50dp`. The guard pulls the *narrow* pair down
 * rather than pushing the wide pair up, because a hero losing separation should lose it
 * toward "tighter corners" — which is still a hero — and not toward "rounder everywhere",
 * which is a card.
 *
 * @param variation how much of the row's budget to spend, 0..1. **0f is the off switch:**
 * every corner returns to the base and [folioShapeFor] hands back the [FolioShapes]
 * instance itself, so a build with the rollout flag off renders today's shapes *by the
 * same objects*, not merely by equal values. Above 1f the input is clamped: the budget is
 * a ceiling, and a caller able to inflate it is a caller able to inflate past the disjoint
 * class ranges the tiers rest on.
 */
internal fun folioCornersFor(seed: Int, row: FolioShapeRow, variation: Float = 1f): FolioCorners {
    if (row.pinned) return row.base
    // A NaN budget means the caller's flag has not resolved yet; resting at the base is
    // the only answer that cannot put a stranger radius on screen for one frame.
    val v = if (variation.isNaN()) 0f else variation.coerceIn(0f, 1f)
    if (v <= 0f) return row.base

    val solved = FolioCorners.of { corner ->
        val home = row.base[corner]
        if (!row.mask[corner] || row.delta[corner] == 0.dp) {
            home
        } else {
            val step = (row.delta[corner].value * v * unitOffset(seed, row.salt, corner)).roundToInt()
            (home.value + step).coerceIn(0f, FolioMaxCorner.value).dp
        }
    }
    if (row.guard == 0.dp) return solved

    // Rule 13, executable: min(wide) − max(narrow) ≥ guard, for every seed by construction.
    // Written as a loop rather than filter/minOf because this runs per item, and the
    // generic forms would box every Dp they touch.
    val wide = row.widePair
    var leastWide = FolioMaxCorner.value
    for (corner in 0..3) {
        val radius = solved[corner].value
        if (wide[corner] && radius < leastWide) leastWide = radius
    }
    val limit = (leastWide - row.guard.value).coerceAtLeast(0f).dp
    return FolioCorners.of { corner ->
        val at = solved[corner]
        if (wide[corner] || at <= limit) at else limit
    }
}

/**
 * The object's own silhouette: pure, deterministic in `(seed, row, variation)`, and
 * allocation-free whenever the solve lands on the base — every pinned class, every free
 * class at `variation = 0f` — because then the shipped [FolioShapes] instance comes back
 * rather than a structurally identical stranger.
 */
fun folioShapeFor(seed: Int, row: FolioShapeRow, variation: Float = 1f): Shape =
    folioCornersFor(seed, row, variation).shapeOrBase(row)

/** Class-keyed [folioShapeFor]. */
fun folioShapeFor(seed: Int, klass: FolioShapeClass, variation: Float = 1f): Shape =
    folioShapeFor(seed, FolioShapeFamily[klass], variation)

private fun FolioCorners.shapeOrBase(row: FolioShapeRow): Shape =
    if (sameShapeAs(row.base)) row.baseShape else toShape()

/**
 * The static silhouette, cached by identity so a list holds one [Shape] per item and zero
 * per frame. `RoundedCornerShape` rebuilds a RoundRect on each `createOutline`, so
 * returning a fresh instance per recomposition would move that cost into the frame
 * without anyone writing a loop; keying the cache on `seed` is also what keeps a book's
 * shape attached to the book across a re-sort.
 *
 * [variation] is the rollout knob (see [folioCornersFor]) — pass
 * `if (settings.ambientColor) 1f else 0f`. That flag's own promise is that off restores
 * the previous look byte-for-byte, and `0f` keeps it here: the shipped [FolioShapes]
 * instance comes back, so an off build is not a rendering of the old shapes but the old
 * shapes.
 */
@Composable
fun rememberFolioShape(seed: Int, klass: FolioShapeClass, variation: Float = 1f): Shape =
    remember(seed, klass, variation) { folioShapeFor(seed, klass, variation) }

/** Row-keyed [rememberFolioShape], for the variants that have no class of their own. */
@Composable
fun rememberFolioShape(seed: Int, row: FolioShapeRow, variation: Float = 1f): Shape =
    remember(seed, row, variation) { folioShapeFor(seed, row, variation) }

/**
 * The animated form for travel *into an object's own silhouette* — entry, press, a flag
 * fading in. `fraction = 0` is the class's vocabulary base and `fraction = 1` is
 * [folioShapeFor] for this seed; both endpoints come back as the exact static instances,
 * so an animation that has not started or has settled is indistinguishable from one that
 * never ran.
 *
 * `motion = false` (the caller's `rememberMotionEnabled()`, hoisted in composition and
 * passed down) collapses the whole ramp to the static family value: reduce-motion skips
 * the travel, it does not park the object at the neutral end.
 *
 * Decorative paths only — see the header.
 */
fun folioShapeForming(
    seed: Int,
    row: FolioShapeRow,
    fraction: Float,
    variation: Float = 1f,
    motion: Boolean = true,
): Shape {
    val solved = folioCornersFor(seed, row, variation)
    return when (val f = travelled(fraction, motion)) {
        0f -> row.baseShape
        1f -> solved.shapeOrBase(row)
        else -> row.base.lerpTo(solved, f).shapeOrBase(row)
    }
}

/** Class-keyed [folioShapeForming]. */
fun folioShapeForming(
    seed: Int,
    klass: FolioShapeClass,
    fraction: Float,
    variation: Float = 1f,
    motion: Boolean = true,
): Shape = folioShapeForming(seed, FolioShapeFamily[klass], fraction, variation, motion)

/**
 * The animated form for a *cross-class* morph — a hero collapsing into the masthead is
 * [FolioShapeFamily.hero] travelling to [FolioShapeFamily.edgeStart] — where `fraction = 0`
 * is [folioShapeFor] on [from] and `1` is [folioShapeFor] on [to]. The seed is held
 * constant across both, so the object keeps its identity while it changes what kind of
 * thing it is. `motion = false` lands on [to], the state the motion was travelling toward.
 *
 * Decorative paths only, and never `LocalFolioAmbient`: a morph that read the room would
 * tie the geometry of one object to a value that changes when a *different* screen's cover
 * is hoisted, and the morph would move under a finger that is not touching it.
 */
fun folioShapeMorph(
    seed: Int,
    from: FolioShapeRow,
    to: FolioShapeRow,
    fraction: Float,
    variation: Float = 1f,
    motion: Boolean = true,
): Shape {
    val f = travelled(fraction, motion)
    val fromCorners = folioCornersFor(seed, from, variation)
    if (f == 0f) return fromCorners.shapeOrBase(from)
    val toCorners = folioCornersFor(seed, to, variation)
    return when (f) {
        1f -> toCorners.shapeOrBase(to)
        else -> fromCorners.lerpTo(toCorners, f).toShape()
    }
}

/**
 * The caller's fraction as this file will use it: clamped to the travel, and collapsed
 * onto its settled end when motion is off or when the fraction has not resolved yet
 * (`coerceIn` passes NaN through, and an unresolved collapse state should show the object
 * at rest rather than smeared between two silhouettes).
 */
private fun travelled(fraction: Float, motion: Boolean): Float = when {
    !motion || fraction.isNaN() -> 1f
    else -> fraction.coerceIn(0f, 1f)
}

/**
 * One seed's legal corner offset, as a signed float in `[-1, 1)`.
 *
 * A splitMix-style finalisation of the seed rather than `Random(seed)`: a silhouette has
 * to reproduce across processes and rebuilds, so it cannot depend on a shared generator's
 * state, and it has to be cheap enough to run per item without allocating one.
 */
private fun unitOffset(seed: Int, salt: Int, corner: Int): Float {
    var h = seed * 0x9E3779B1 + salt * 0x85EBCA77 + corner * 0xC2B2AE3D
    h = (h xor (h ushr 15)) * 0x2C1B3C6D
    h = (h xor (h ushr 12)) * 0x297A2D39
    h = h xor (h ushr 15)
    // 18 bits over [0, 2), biased to (-1, 1): enough resolution that an object's corners
    // never share a draw, and no tie survives the round to whole dp. |result| <= 1, which
    // is what keeps the solver inside base ± delta by construction.
    return (h ushr 10 and 0x3FFFF).toFloat() / 131072f - 1f
}
