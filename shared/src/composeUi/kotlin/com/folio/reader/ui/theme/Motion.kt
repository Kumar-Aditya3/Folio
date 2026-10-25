package com.folio.reader.ui.theme

import androidx.compose.animation.core.AnimationSpec
import androidx.compose.animation.core.Easing
import androidx.compose.animation.core.FiniteAnimationSpec
import androidx.compose.animation.core.snap
import androidx.compose.animation.core.spring
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.Immutable
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import com.folio.reader.settings.MotionPreferences
import com.folio.reader.settings.MotionPreset
import com.folio.reader.settings.ReduceMotionMode

/**
 * The app's motion, as data.
 *
 * ## The problem this replaces
 *
 * Before this file, Folio's timing lived in four places at once: eight `FolioTokens` values, ~29
 * `tween`/`spring` call sites with literal durations (160/180/200/220/240/260/300/320/800),
 * six spring literals, twenty Mirage `speed` literals, nine slow-layer periods across four
 * packages — and **four different implementations of reduce-motion** (`speed = 0`, frozen phases,
 * a `Neutral` sentinel, park-at-the-end-of-the-lap). Nothing could be tuned without hunting, the
 * same semantic value was defined in three files (`motionFast + 60`), and two animations on one
 * screen could disagree about what "arriving" looks like.
 *
 * ## The shape
 *
 * - [MotionKey] — one entry per animation the app has, named for the thing that moves.
 * - [motionProfile] — the **fixed, system-tuned** table: duration, easing, spring, intensity and
 *   dwell for every key, chosen for that animation's purpose. `internal`, exhaustive, and with no
 *   settings path: this is the app's opinion, not the reader's.
 * - [MotionPreferences] — the **user-adjustable** layer: a preset and seven channel levers, each a
 *   multiplier. A lever cannot invent a curve; it can only play the tuned one faster or slower.
 * - [resolveMotion] — pure: base → device tier → preset → levers → reduce-motion (last, wins).
 *
 * Everything here is platform-neutral, so `desktopTest` pins the whole system without a device.
 *
 * ## Reading it at a call site
 *
 * ```kotlin
 * val spec = rememberMotionSpec(MotionKey.SelectionToolbar)
 * val alpha by animateFloatAsState(if (shown) 1f else 0f, tween(spec.durationMs.toInt(), easing = spec.easing))
 * ```
 *
 * ## The one thing to know about the field
 *
 * `speed` scales the shader's own clock, **not** the redraw rate: the Mirage field repaints every
 * vsync whatever the speed, so changing it costs no frames. Cost is governed separately, by
 * `FolioFieldCost` (render scale) and `FolioFieldOccluder` (presence).
 */

/** Which lever a spec answers to. See [MotionPreferences]. */
enum class MotionChannel {
    /** The living background: the field and every slow layer. */
    FIELD,
    /** Reader chrome: bars, the floating rail, side panels, the selection toolbar. */
    CHROME,
    /** Sheets, menus, dialogs, overlays and navigation push/pop. */
    SHEET,
    /** The cover morph, the tab dissolve, the plate dissolve, the glyph swap. */
    MORPH,
    /** Progress, shimmer, sweeps, the sync pill, the status banner. */
    FEEDBACK,
    /** Entry staggers and chart growth. */
    ENTRY,
}

/**
 * Every animation the app has, named for what moves.
 *
 * Exhaustive on purpose: `motionProfile` is a `when` over this enum, so adding an animation means
 * giving it a tuned spec rather than silently inheriting one — the argument `FolioFieldCost` already
 * makes for render scale, applied to time.
 */
enum class MotionKey {
    // ── Navigation ─────────────────────────────────────────────────────────────
    /** Pushing a route (masthead fade, content slide). */
    NavPush,

    /** Popping back to the previous route. */
    NavPop,

    /** The Home ↔ Library tab dissolve, which shares the morph scope but must not travel. */
    TabDissolve,

    /**
     * The cover morph: a list thumbnail ↔ the detail/reader plate, with its paired title and
     * author text. The longest motion in the app, and the only one whose object crosses the whole
     * viewport — so it is the one key with a hard ceiling ([MORPH_MAX_MS]), because past it the eye
     * reads a snap-and-settle rather than one continuous object moving.
     */
    CoverMorph,

    /** The Home shelf's direct-manipulation turn and release settle. */
    ReadingShelfTurn,

    // ── Overlays ───────────────────────────────────────────────────────────────
    /** An anchored menu or dialog arriving. */
    OverlayEnter,

    /** An anchored menu or dialog leaving. */
    OverlayExit,

    /** A bottom sheet rising, including its grow spring. */
    SheetEnter,

    /** A bottom sheet leaving. */
    SheetExit,

    /** The settle after a drag-dismiss gesture releases. */
    SheetDismiss,

    // ── Reader ─────────────────────────────────────────────────────────────────
    /** The reader's bars showing and hiding on a tap. */
    ReaderChromeToggle,

    /** The reader's floating action rail. */
    ReaderRail,

    /** The reader's side panels (contents, annotations). */
    ReaderSidePanels,

    /** The selection toolbar that appears over selected text. */
    SelectionToolbar,

    /** The cover plate dissolving once the chapter has painted. */
    ReaderPlateDissolve,

    /** The bookmark glyph filling or emptying. */
    BookmarkGlyphSwap,

    // ── Surfaces and press ─────────────────────────────────────────────────────
    /** The press scale on a tappable surface. */
    PressScale,

    /** The press focal highlight. */
    PressFocal,

    /** The segmented control's sliding indicator. */
    SegmentedIndicator,

    /** A shelf row's content fading from one state to the next. */
    ShelfFadeSwap,

    // ── Feedback ───────────────────────────────────────────────────────────────
    /** A progress ring filling. */
    ProgressRing,

    /** Progress bars growing. */
    ProgressBars,

    /** The statistics dial's number sweeping. */
    GoalDial,

    /** The loading shimmer band crossing an unarrived pane. */
    ShimmerSweep,

    /** The specular band crossing the nav capsule on a tab change. */
    LiquidSweep,

    /** The cloud-sync pill appearing and leaving. */
    SyncPill,

    /** The sync pill's repeating pulse. */
    SyncPulse,

    /** The status banner sliding in and out. */
    StatusBanner,

    /** The end-of-chapter chip arriving. */
    EndOfChapterChip,

    /** How long the end-of-chapter chip stays before fading. A dwell, not a transition. */
    ChipDwell,

    /** The statistics screen's handoff fade. */
    StatsHandoffFade,

    /** Entry stagger and chart bar growth. */
    ChartEntry,

    /** Manga screens' content swap. */
    MangaContentSwap,

    // ── Background (see FieldMotion.kt for the per-family values) ──────────────
    /** The field's speed factor row. Carries no duration; see [ResolvedMotion.fieldSpeedFactor]. */
    FieldMotion,

    /** The field's amplitude factor row. Carries no duration; see [ResolvedMotion.fieldIntensityFactor]. */
    FieldIntensity,

    /**
     * The field's pattern size: how many blobs, how wide the cells, how far apart the dots, how
     * many bands — whatever the family draws. A factor row like the other two; see
     * [ResolvedMotion.fieldScaleFactor].
     */
    FieldScale,

    /** The ambient light's horizontal swing period. */
    AmbientSwing,

    /** The ambient light's vertical breath period. */
    AmbientBreath,

    /** The silk ground plane's drift periods. */
    SilkDrift,

    /** The themed rim's travelling beam period. */
    RimBeam,

    /** The lamp's bloom period. */
    LampBreath,
}

/** A spring's two numbers, so a spring can live in the table like everything else. */
@Immutable
data class MotionSpring(val dampingRatio: Float, val stiffness: Float)

/**
 * One animation's resolved timing.
 *
 * [intensity] is deliberately unitless: it means "how much of this motion", and each call site
 * reads it in its own units — a fraction of the surface height for a slide, an alpha for a fade, a
 * scale delta for a press. Keeping it a single 0..1 amplitude is what lets one lever
 * ([MotionPreferences.progressFeedback] and friends) mean something to animations that share no
 * other parameter.
 */
@Immutable
data class MotionSpec(
    /** Duration in ms. `0` means instant — the honest spelling of "no animation". */
    val durationMs: Long,
    val easing: Easing = FolioEasing.Enter,
    /** Present when the animation is spring-driven; then [durationMs] is unused by the call site. */
    val spring: MotionSpring? = null,
    /** Amplitude, 0..1. See the class doc. */
    val intensity: Float = 1f,
    /** A dwell's length in ms, for the timers ([MotionKey.ChipDwell]). */
    val dwellMs: Long = 0,
    /** The tick a slow layer reads its phase on, for the continuous background layers. */
    val tickMs: Long = 0,
    val channel: MotionChannel,
)

/**
 * **The fixed table.** One tuned row per animation, chosen for that animation's purpose:
 *
 * - things the reader *did* (press, selection, a tap that reveals chrome) are quick and immediate;
 * - things that *arrive* decelerate ([FolioEasing.Enter]) and things that *leave* accelerate
 *   ([FolioEasing.Exit]), which is why entries and exits are separate keys rather than one value
 *   with a sign;
 * - never-ending motion is linear and slow enough to stop reading as animation;
 * - anything purely decorative (shimmer, sweep, pulse) is the first thing reduce-motion drops.
 *
 * Values that were already shipped are referenced from [FolioTokens] rather than re-typed, so the
 * table is provably the old behaviour where it claims to be (`MotionSchemaTest` asserts it).
 */
internal fun motionProfile(key: MotionKey): MotionSpec = when (key) {
    MotionKey.ReadingShelfTurn -> MotionSpec(420L, FolioEasing.Enter,
        spring = MotionSpring(dampingRatio = .9f, stiffness = 400f), channel = MotionChannel.MORPH)
    // ── Navigation ─────────────────────────────────────────────────────────────
    // A push is a state change, not a journey: standard duration, a short slide, no drama.
    MotionKey.NavPush -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, intensity = 1f, channel = MotionChannel.SHEET)
    // An exit the reader asked for gets out of the way faster than an entry arrives.
    MotionKey.NavPop -> MotionSpec(180L, FolioEasing.Exit, channel = MotionChannel.SHEET)
    // Peers switching places: the cover morph's scope, but no travel.
    MotionKey.TabDissolve -> MotionSpec(FolioTokens.motionTabMorph, FolioEasing.Enter, channel = MotionChannel.MORPH)
    // The one travelling object in the app: `motionMorph`, on the symmetric `Travel` curve, so it
    // reads as one thing that starts and stops rather than as a fade between two positions.
    MotionKey.CoverMorph -> MotionSpec(FolioTokens.motionMorph, FolioEasing.Travel, channel = MotionChannel.MORPH)

    // ── Overlays ───────────────────────────────────────────────────────────────
    // An anchored pane comes out of its trigger: quick, linear-out, scaling from 0.96.
    MotionKey.OverlayEnter -> MotionSpec(160L, FolioEasing.Enter, intensity = 0.96f, channel = MotionChannel.SHEET)
    MotionKey.OverlayExit -> MotionSpec(110L, FolioEasing.Exit, channel = MotionChannel.SHEET)
    // A sheet is the largest thing that moves, and it settles on a soft spring.
    MotionKey.SheetEnter -> MotionSpec(FolioTokens.motionEmphasis, FolioEasing.Enter, MotionSpring(0.86f, 1500f), channel = MotionChannel.SHEET)
    MotionKey.SheetExit -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Exit, channel = MotionChannel.SHEET)
    // Drag-dismiss follows the finger; this spec is only the settle after release.
    MotionKey.SheetDismiss -> MotionSpec(160L, FolioEasing.Enter, MotionSpring(0.75f, 700f), channel = MotionChannel.SHEET)

    // ── Reader ─────────────────────────────────────────────────────────────────
    // Chrome must never compete with the page: standard duration, a third of the height.
    MotionKey.ReaderChromeToggle -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.CHROME)
    // The rail is part of that same chrome family, so it sits just above it rather than at a
    // stray 300 ms that made the two disagree.
    MotionKey.ReaderRail -> MotionSpec(260L, FolioEasing.Enter, channel = MotionChannel.CHROME)
    // Panels travel further than the rail, so they take slightly longer.
    MotionKey.ReaderSidePanels -> MotionSpec(280L, FolioEasing.Enter, channel = MotionChannel.CHROME)
    // A toolbar that follows a selection has to feel attached to the finger that made it.
    MotionKey.SelectionToolbar -> MotionSpec(180L, FolioEasing.Enter, channel = MotionChannel.CHROME)
    MotionKey.ReaderPlateDissolve -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.CHROME)
    MotionKey.BookmarkGlyphSwap -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.CHROME)

    // ── Surfaces and press ─────────────────────────────────────────────────────
    // Press is the fastest thing in the app: it must feel like the surface moved *now*.
    MotionKey.PressScale -> MotionSpec(0L, spring = MotionSpring(0.78f, 900f), intensity = 0.976f, channel = MotionChannel.CHROME)
    MotionKey.PressFocal -> MotionSpec(0L, spring = MotionSpring(0.75f, 700f), channel = MotionChannel.CHROME)
    MotionKey.SegmentedIndicator -> MotionSpec(0L, spring = MotionSpring(0.78f, 480f), channel = MotionChannel.CHROME)
    // `motionFast + 60` in / `motionFast` out, defined once instead of recomputed in three files.
    MotionKey.ShelfFadeSwap -> MotionSpec(FolioTokens.motionFast + 60, FolioEasing.Enter, channel = MotionChannel.MORPH)

    // ── Feedback ───────────────────────────────────────────────────────────────
    MotionKey.ProgressRing -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    MotionKey.ProgressBars -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    // A number sweeping to its value should settle, not sweep linearly for most of a second.
    MotionKey.GoalDial -> MotionSpec(700L, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    MotionKey.ShimmerSweep -> MotionSpec(FolioTokens.motionShimmer, FolioEasing.Continuous, intensity = 0.5f, channel = MotionChannel.FEEDBACK)
    MotionKey.LiquidSweep -> MotionSpec(FolioTokens.motionLiquidSweep, FolioEasing.Continuous, channel = MotionChannel.FEEDBACK)
    // Four literals (220/260/200/240) collapsed to one enter/exit pair.
    MotionKey.SyncPill -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    MotionKey.SyncPulse -> MotionSpec(700L, FolioEasing.Enter, dwellMs = 2400L, channel = MotionChannel.FEEDBACK)
    // Likewise four literals (180/220/160/200).
    MotionKey.StatusBanner -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    MotionKey.EndOfChapterChip -> MotionSpec(FolioTokens.motionStandard, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)
    MotionKey.ChipDwell -> MotionSpec(0L, FolioEasing.Timer, dwellMs = FolioTokens.chipAutoDismiss, channel = MotionChannel.FEEDBACK)
    MotionKey.StatsHandoffFade -> MotionSpec(180L, FolioEasing.Enter, channel = MotionChannel.FEEDBACK)

    // ── Entry ──────────────────────────────────────────────────────────────────
    // `motionEmphasis`; the stagger (40 ms, capped at 80) is the intensity's companion and lives
    // in EntryMotion's call site, fed from this spec's duration.
    MotionKey.ChartEntry -> MotionSpec(FolioTokens.motionEmphasis, FolioEasing.Enter, channel = MotionChannel.ENTRY)
    // Manga screens disagreed with each other (220/160 vs 200); one pair now.
    MotionKey.MangaContentSwap -> MotionSpec(200L, FolioEasing.Enter, channel = MotionChannel.ENTRY)

    // ── Background ─────────────────────────────────────────────────────────────
    // Factor rows: the real numbers are per-family (FieldMotion.kt) and the resolved factors are
    // exposed on ResolvedMotion.
    MotionKey.FieldMotion -> MotionSpec(0L, FolioEasing.Continuous, channel = MotionChannel.FIELD)
    MotionKey.FieldIntensity -> MotionSpec(0L, FolioEasing.Continuous, channel = MotionChannel.FIELD)
    MotionKey.FieldScale -> MotionSpec(0L, FolioEasing.Continuous, channel = MotionChannel.FIELD)
    // The slow layers. Long enough that the eye reads a room rather than an animation; the tick is
    // how often their phase is re-read (a draw-phase read, so it costs no recomposition).
    MotionKey.AmbientSwing -> MotionSpec(FieldMotion.ambientSwingPeriodMs, FolioEasing.Continuous, tickMs = FieldMotion.ambientTickMs, channel = MotionChannel.FIELD)
    MotionKey.AmbientBreath -> MotionSpec(FieldMotion.ambientBreathPeriodMs, FolioEasing.Continuous, tickMs = FieldMotion.ambientTickMs, channel = MotionChannel.FIELD)
    MotionKey.SilkDrift -> MotionSpec(FieldMotion.silkPeriodMs, FolioEasing.Continuous, tickMs = FieldMotion.silkTickMs, channel = MotionChannel.FIELD)
    MotionKey.RimBeam -> MotionSpec(FieldMotion.rimPeriodMs, FolioEasing.Continuous, tickMs = FieldMotion.rimTickMs, channel = MotionChannel.FIELD)
    MotionKey.LampBreath -> MotionSpec(FieldMotion.lampPeriodMs, FolioEasing.Continuous, tickMs = FieldMotion.lampTickMs, channel = MotionChannel.FIELD)
}

/** The device class a spec is resolved for. Derived, never set by the reader. */
enum class MotionTier { STANDARD, CONSTRAINED }

/**
 * Which tier this device is in.
 *
 * The same verdict the glass already derives from `ActivityManager.memoryClass`
 * ([com.folio.reader.ui.components.GlassCapabilities.budget]) plus whether the platform can blur —
 * a device that cannot blur has no lens to animate either. `budget` is `1f` on every un-provided
 * tree (desktop, previews, tests), so nothing degrades where the capability is unknown.
 */
fun motionTierFor(glassBudget: Float, canBlur: Boolean = true): MotionTier =
    if (glassBudget < 1f || !canBlur) MotionTier.CONSTRAINED else MotionTier.STANDARD

/**
 * A profile's seven channel factors.
 *
 * **One convention throughout: a higher factor is more motion.** A duration divides by its factor
 * (so 1.25 is a quarter faster), while the field's speed and intensity multiply by theirs. Getting
 * this wrong is the easiest mistake to make in this file — an earlier draft wrote CALM's chrome as
 * 1.4, which made the calm profile 40% *faster* — so the test suite asserts the direction
 * (`calmIsSlowerAndLivelyIsFasterForEveryTransition`) rather than trusting the numbers.
 */
private data class ProfileFactors(
    val field: Float,
    val fieldIntensity: Float,
    val fieldScale: Float,
    val chrome: Float,
    val sheets: Float,
    val morph: Float,
    val feedback: Float,
    val entry: Float,
)

private fun MotionPreset.factors(): ProfileFactors = when (this) {
    // Slower chrome and feedback, a quieter room at half speed.
    // Size stays 1: a profile is a *tempo*, and Calm is not a smaller room.
    MotionPreset.CALM -> ProfileFactors(0.5f, 0.6f, 1f, 0.75f, 0.8f, 0.85f, 0.75f, 0.85f)
    // Exactly the shipped values.
    MotionPreset.STANDARD -> ProfileFactors(1f, 1f, 1f, 1f, 1f, 1f, 1f, 1f)
    // Quicker chrome and feedback, a livelier room.
    MotionPreset.LIVELY -> ProfileFactors(1.5f, 0.85f, 1f, 1.25f, 1.2f, 1.15f, 1.3f, 1.2f)
}

private fun ProfileFactors.forChannel(channel: MotionChannel): Float = when (channel) {
    MotionChannel.FIELD -> field
    MotionChannel.CHROME -> chrome
    MotionChannel.SHEET -> sheets
    MotionChannel.MORPH -> morph
    MotionChannel.FEEDBACK -> feedback
    MotionChannel.ENTRY -> entry
}

/** Bounds a transition duration. Long enough to read, short enough not to be in the way. */
private const val MIN_DURATION_MS = 40L
private const val MAX_DURATION_MS = 800L

/** A slow layer's period bounds — minutes are fine, 800 ms is not. */
private const val MIN_PERIOD_MS = 4_000L
private const val MAX_PERIOD_MS = 240_000L

/**
 * A loop's bounds. A loop is a *cycle*, not a transition: the shimmer's 1200 ms is how long one
 * band takes to cross a pane, and clamping it to the transition ceiling of 800 ms would make the
 * app's calmest loading cue into its most hurried one. Bounded instead so a lever cannot turn a
 * loop into a strobe or into a still.
 */
private const val MIN_LOOP_MS = 200L
private const val MAX_LOOP_MS = 4_000L

/** The morph's ceiling, so even LIVELY keeps `DesignSystemTest`'s "one continuous object" promise. */
private const val MORPH_MAX_MS = 600L

/** Under a constrained tier the morph is clamped harder — it is the most expensive foreground motion. */
private const val MORPH_CONSTRAINED_MAX_MS = 380L

/** Reduced motion's ceiling. Past this it is not reduced. */
private const val REDUCED_MAX_MS = 140L

/** The faintest a state change may become under reduced motion — never invisible. */
private const val REDUCED_MIN_INTENSITY = 0.35f

/**
 * Purely decorative motion: the app is complete without it, so reduce-motion switches it off
 * rather than shortening it. Everything else keeps its shape and gets shorter.
 */
private val DECORATIVE = setOf(MotionKey.ShimmerSweep, MotionKey.LiquidSweep, MotionKey.SyncPulse)

/**
 * The reduced-motion version of a spec: the same animation, shorter, with the decoration removed.
 *
 * Deliberately **not** a freeze and **not** a snap-to-end. A reader who asks for less motion still
 * benefits from knowing that something arrived and from where — a field frozen mid-drift reads as a
 * rendering bug, and a UI that teleports between states is harder to follow than one that moves
 * briefly. So:
 *
 * - a spring becomes an instant (press and the segmented indicator have no duration to shorten);
 * - a transition is capped at [REDUCED_MAX_MS] and keeps its easing, so its *character* survives;
 * - decoration ([DECORATIVE]) is switched off;
 * - a dwell ([MotionKey.ChipDwell]) is a timer, not motion, and is untouched.
 */
internal fun MotionSpec.reducedFor(key: MotionKey): MotionSpec = when {
    key == MotionKey.ChipDwell -> this
    // A background layer is not a transition, and its period is not a duration to shorten. The room
    // calms by *stretching* — a period four times as long reads as air rather than as movement —
    // and by halving its amplitude. Clamping a 47-second period to 140 ms, which is what treating
    // every row as a transition does, would have made the reduced room the fastest thing in the app.
    channel == MotionChannel.FIELD -> copy(
        durationMs = if (durationMs == 0L) 0L else (durationMs * REDUCED_PERIOD_STRETCH).toLong().coerceAtMost(MAX_PERIOD_MS),
        intensity = intensity * 0.5f,
    )
    key in DECORATIVE -> copy(intensity = 0f, spring = null)
    spring != null -> copy(durationMs = 0L, spring = null)
    durationMs == 0L -> this
    else -> copy(
        durationMs = durationMs.coerceAtMost(REDUCED_MAX_MS),
        intensity = intensity.coerceIn(REDUCED_MIN_INTENSITY, 1f),
    )
}

/** How much longer a slow layer's period becomes under reduce-motion. */
private const val REDUCED_PERIOD_STRETCH = 4f

/** The device tier's own adjustment: cheaper shapes, same design. */
private fun MotionSpec.underTier(tier: MotionTier, key: MotionKey): MotionSpec = when (tier) {
    MotionTier.STANDARD -> this
    MotionTier.CONSTRAINED -> {
        val cheaper = if (spring != null) copy(durationMs = durationMs.coerceAtLeast(MIN_DURATION_MS), spring = null) else this
        when (key) {
            // The most expensive foreground motion, and the one a constrained device notices.
            MotionKey.CoverMorph -> cheaper.copy(durationMs = cheaper.durationMs.coerceAtMost(MORPH_CONSTRAINED_MAX_MS))
            // Decoration is halved rather than removed — a constrained device is not a reduced-motion reader.
            MotionKey.ShimmerSweep, MotionKey.LiquidSweep, MotionKey.SyncPulse ->
                cheaper.copy(intensity = cheaper.intensity * 0.5f)
            else -> cheaper
        }
    }
}

/** A lever multiplies the preset's factor; a lever set to `null` follows the preset. */
/** The factor these preferences resolve to for [channel]: the lever if set, else the preset. */
internal fun MotionPreferences.factorFor(channel: MotionChannel): Float {
    val base = preset.factors().forChannel(channel)
    val lever = when (channel) {
        MotionChannel.FIELD -> fieldMotion
        MotionChannel.CHROME -> readerChrome
        MotionChannel.SHEET -> sheetsOverlays
        MotionChannel.MORPH -> coverMorph
        MotionChannel.FEEDBACK -> progressFeedback
        MotionChannel.ENTRY -> chartEntry
    }
    return lever ?: base
}

/**
 * The whole app's motion, resolved. Immutable and cheap to hand down a tree.
 *
 * [fieldSpeedFactor] and [fieldIntensityFactor] are the background's two direct factors (they are
 * multipliers, not durations, which is why they are not read off a [MotionSpec]).
 */
@Immutable
class ResolvedMotion internal constructor(
    private val table: Map<MotionKey, MotionSpec>,
    /** True when reduced motion is in force for this resolution. */
    val reduced: Boolean,
    /** Multiplier on the field's per-family speed. `1f` is the authored rate. */
    val fieldSpeedFactor: Float,
    /** Multiplier on the field's per-family amplitude. `1f` is the authored value. */
    val fieldIntensityFactor: Float,

    /**
     * Multiplier on the family's own pattern size — the blobs, cells, dot spacing and band
     * counts it draws. `1f` is the authored size, so a fresh install is untouched.
     */
    val fieldScaleFactor: Float,
    /** Which device class this was resolved for. */
    val tier: MotionTier,
) {
    operator fun get(key: MotionKey): MotionSpec = table.getValue(key)

    /** The dwell for a key, for the timers that read one. */
    fun dwell(key: MotionKey): Long = get(key).dwellMs

    companion object {
        /**
         * The un-provided default: what a preview, a unit test or a tree outside the app root gets.
         * The tuned table with no preset, no levers, no tier and no reduction — i.e. exactly the
         * shipped motion, so a surface rendered outside the root animates as it always did.
         */
        val Preview: ResolvedMotion = resolveMotion(MotionPreferences(), MotionTier.STANDARD, systemReduced = false)
    }
}

/** The three background levers, so a slider can show what `Auto` currently resolves to. */
internal enum class FieldLever { MOTION, INTENSITY, SCALE }

/**
 * The factor a field lever resolves to: the lever if the reader has moved it, otherwise the
 * preset's own. This is what an `Auto` slider should sit at, so dragging away from it is a
 * visible change rather than a jump.
 */
internal fun MotionPreferences.fieldFactor(lever: FieldLever): Float = when (lever) {
    FieldLever.MOTION -> factorFor(MotionChannel.FIELD)
    FieldLever.INTENSITY -> fieldIntensity ?: preset.factors().fieldIntensity
    FieldLever.SCALE -> fieldScale ?: preset.factors().fieldScale
}

/**
 * Resolves the app's motion. Pure, so `desktopTest` can pin every combination.
 *
 * Order matters and is deliberate: **base → device tier → preset → levers → reduce-motion.** The
 * tier is applied before the reader's own choice so a lever can undo it (a reader who wants the
 * morph slow on a constrained device gets it), and reduce-motion is applied last so nothing can
 * override it.
 */
fun resolveMotion(
    prefs: MotionPreferences,
    tier: MotionTier,
    systemReduced: Boolean,
): ResolvedMotion {
    val reduced = when (prefs.reduceMotion) {
        ReduceMotionMode.FOLLOW_SYSTEM -> systemReduced
        ReduceMotionMode.REDUCED -> true
        ReduceMotionMode.FULL -> false
    }
    val speedFactor = prefs.factorFor(MotionChannel.FIELD)
    val intensityFactor = prefs.fieldIntensity ?: prefs.preset.factors().fieldIntensity
    // Scale has no channel lever to clash with: it is its own field, not the FIELD tempo.
    val scaleFactor = prefs.fieldScale ?: prefs.preset.factors().fieldScale

    val table = MotionKey.entries.associateWith { key ->
        val base = motionProfile(key)
        val tiered = base.underTier(tier, key)
        val scaled = tiered.scaledBy(prefs.factorFor(tiered.channel), key, tier)
        if (reduced) scaled.reducedFor(key) else scaled
    }
    return ResolvedMotion(
        table = table,
        reduced = reduced,
        // Under reduce-motion the room does not stop: it crawls. A field frozen mid-drift reads as
        // a rendering bug, and the reader asked for less motion, not for a still image.
        fieldSpeedFactor = speedFactor * if (reduced) REDUCED_FIELD_SPEED else 1f,
        // The tier's own field damping rides on top of the reader's choice.
        fieldIntensityFactor = intensityFactor *
            if (tier == MotionTier.CONSTRAINED) 0.7f else 1f,
        // Size is a taste question, not a performance one: no tier quietly changes it.
        fieldScaleFactor = scaleFactor,
        tier = tier,
    )
}

/** How much of its rate the field keeps under reduce-motion. Slow enough to read as still. */
private const val REDUCED_FIELD_SPEED = 0.15f

/**
 * Applies a channel factor.
 *
 * Durations **divide** by the factor (a factor of 2 is twice as fast), which is why a lever of 1f
 * is the identity and why a lever can never produce a duration the table never had. A slow layer's
 * *period* divides too, so "more field motion" means a shorter period — the same direction as
 * everywhere else.
 */
private fun MotionSpec.scaledBy(factor: Float, key: MotionKey, tier: MotionTier): MotionSpec {
    if (factor == 1f || durationMs == 0L) return this
    val scaled = (durationMs / factor).toLong()
    val bounded = when {
        // A slow layer: minutes are legitimate.
        channel == MotionChannel.FIELD -> scaled.coerceIn(MIN_PERIOD_MS, MAX_PERIOD_MS)
        // A loop: a cycle, not a transition — see [MIN_LOOP_MS].
        isLoop -> scaled.coerceIn(MIN_LOOP_MS, MAX_LOOP_MS)
        key == MotionKey.CoverMorph ->
            scaled.coerceIn(MIN_DURATION_MS, if (tier == MotionTier.CONSTRAINED) MORPH_CONSTRAINED_MAX_MS else MORPH_MAX_MS)
        else -> scaled.coerceIn(MIN_DURATION_MS, MAX_DURATION_MS)
    }
    return copy(durationMs = bounded)
}

/**
 * Whether this spec is a repeating cycle rather than a one-shot transition.
 *
 * The tell is the easing: [FolioEasing.Continuous] is what the table gives everything that never
 * ends (the shimmer, the liquid sweep, the sync pulse, and every background layer), because a loop
 * that eases reads as a series of starts and stops. Those are bounded as loops, not as transitions.
 */
internal val MotionSpec.isLoop: Boolean
    get() = easing === FolioEasing.Continuous && channel != MotionChannel.FIELD

/**
 * The app's resolved motion, for a call site to read.
 *
 * Provided once at the app root (see `MainActivity` / desktop `Main`), defaulting to
 * [ResolvedMotion.Preview] so a preview, a test or a tree outside the root renders the shipped
 * motion rather than inventing one — the same contract `LocalFolioBackdrop` keeps for the room.
 */
val LocalMotion = compositionLocalOf { ResolvedMotion.Preview }

/**
 * The reader's motion preferences, as provided by the app root. Read by [rememberMotion]; the
 * settings UI takes its copy as a parameter like every other settings panel.
 */
val LocalMotionPreferences = compositionLocalOf { MotionPreferences() }

/**
 * Resolves the app's motion for [prefs], reading the device's tier and the platform's
 * reduce-motion flag from the tree. The app root calls this once and provides [LocalMotion].
 *
 * Keyed on the preferences, the flag and the two capability facts, so it re-resolves when any of
 * them changes and never on a frame that did not.
 */
@Composable
fun rememberResolvedMotion(prefs: MotionPreferences): ResolvedMotion {
    val systemReduced = !rememberMotionEnabled()
    val caps = com.folio.reader.ui.components.LocalGlassCapabilities.current
    return remember(prefs, systemReduced, caps.budget, caps.blur) {
        resolveMotion(prefs, motionTierFor(caps.budget, caps.blur), systemReduced)
    }
}

/**
 * Resolves the app's motion from the reader's preferences as provided by the root. A subtree that
 * only needs the resolved table should read [LocalMotion] (via [rememberMotionSpec]) instead.
 */
@Composable
fun rememberMotion(): ResolvedMotion = rememberResolvedMotion(LocalMotionPreferences.current)

/** The spec for one animation. The form every call site uses. */
@Composable
fun rememberMotionSpec(key: MotionKey): MotionSpec = LocalMotion.current[key]

/**
 * The spec for one animation as a Compose [AnimationSpec], which is what `animate*AsState`,
 * `updateTransition` and `AnimatedVisibility` actually take.
 *
 * The conversion is the whole reason a call site no longer has to think about reduce-motion or the
 * device tier: a spring stays a spring on a capable device and becomes a short tween under
 * reduce-motion, an instant under a press, and a snap when the table says there is nothing to
 * animate. A call site that reads this cannot get those cases wrong, which is what the four
 * hand-rolled reduce-motion implementations this replaced all managed to do differently.
 */
@Composable
fun <T> rememberMotionAnimation(key: MotionKey): FiniteAnimationSpec<T> {
    val spec = LocalMotion.current[key]
    return remember(spec) { spec.toAnimationSpec() }
}

/** See [rememberMotionAnimation]. Pure, so a test can pin the mapping without a composition. */
fun <T> MotionSpec.toAnimationSpec(): FiniteAnimationSpec<T> = when {
    spring != null -> spring(dampingRatio = spring.dampingRatio, stiffness = spring.stiffness)
    durationMs <= 0L -> snap()
    else -> tween(durationMillis = durationMs.toInt(), easing = easing)
}
