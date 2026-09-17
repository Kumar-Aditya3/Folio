@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.BoundsTransform
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.tween
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.folio.reader.ui.theme.FolioTokens
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * Shared-element keys for content that morphs between list/grid and
 * detail/reader screens.
 *
 * Keys are stable and unique per item: a book cover uses `"book_cover:${bookId}"`,
 * a manga cover uses `"manga_cover:${mangaId}"`, a document thumbnail uses
 * `"document_cover:${documentId}"`. Both the source (list/grid tile) and the
 * destination (detail/reader plate) must carry the same key for the shared
 * transition to animate between them.
 *
 * Text keys carry the item id plus the field name — `"book_title:42"`,
 * `"book_author:42"` — because a screen morphs up to three text runs off the same
 * item, and the registry needs to pair the title with the title, not with the
 * author. Pair them with [sharedTextOrNoop], which cross-fades while the box
 * morphs; pairing them with `sharedElement` would scale the glyphs instead.
 */
object FolioSharedKeys {
    fun bookCover(bookId: String) = "book_cover:$bookId"
    fun mangaCover(mangaId: String) = "manga_cover:$mangaId"
    fun documentCover(documentId: String) = "document_cover:$documentId"

    fun bookTitle(bookId: String) = "book_title:$bookId"
    fun bookAuthor(bookId: String) = "book_author:$bookId"
    fun mangaTitle(mangaId: String) = "manga_title:$mangaId"
    fun documentTitle(documentId: String) = "document_title:$documentId"

    // ── In-content morphs ────────────────────────────────────────────────
    //
    // These pair two states of the *same* screen rather than a shelf and a
    // detail page: a highlight row becoming the passage inside its note
    // composer, a quote travelling between the stats feed and the quote
    // browser. They exist because the morph scaffolding was built for
    // navigation and then only ever used there — the same registry serves
    // both, and a passage that drifts out of the row it was tapped in is
    // exactly the continuity a shared element is for.
    //
    // Keyed by the source item's id so two morphs running at the same time
    // cannot collide: two live copies of one key is the case the registry has
    // no answer for (see [LocalSharedElementsSuppressed]).
    //
    // **Two rules for adding one here.**
    //
    // 1. *Only pair keys whose two sides are both Compose nodes.* A highlight
    //    **in the page** is rendered as HTML inside the WebView/JCEF surface,
    //    not as a Compose node, so it cannot publish a key and must not be
    //    given one — a key with only one side is inert and reads as a bug.
    //    Pair within Compose.
    // 2. *Add the key when you wire the pair, not before.* A key with no
    //    callers is a promise the app does not keep.

    /**
     * A highlight row travelling into the passage shown at the top of its note
     * composer. Both sides are Compose and show the same text, so the passage
     * appears to lift out of the row the reader tapped.
     */
    fun highlightToNote(highlightId: String) = "highlight_note:$highlightId"
}

/**
 * The timing every cover morph runs on.
 *
 * The API's default `BoundsTransform` is a spring, and Rule 6 bans springs in
 * navigation: a spring's settle time depends on distance travelled, so a cover
 * hopping two rows and a cover travelling the full viewport would land at
 * different moments, and the paired title text — which runs on the same spec —
 * would drift against its own cover. A `tween` on [FolioTokens.motionMorph] makes
 * every morph in the app the same shape regardless of how far it flies.
 *
 * FastOutSlowInEasing is the same curve §13.5 uses for entry progress, so a morph
 * that hands off to a chart or hero animation reads as one continuous motion.
 */
internal val folioMorphBounds = BoundsTransform { _, _ ->
    tween(FolioTokens.motionMorph.toInt(), easing = FastOutSlowInEasing)
}

/**
 * CompositionLocal holding the SharedTransitionScope provided by SharedTransitionLayout.
 * On desktop/other targets, this is null and the shared-element modifier is a no-op.
 */
internal val LocalSharedTransitionScope = compositionLocalOf<SharedTransitionScope?> { null }

/** Holder for the two scopes required by the shared-element modifier. */
internal class SharedElementScopes(
    val sharedTransitionScope: SharedTransitionScope,
    val animatedVisibilityScope: AnimatedVisibilityScope,
)

/**
 * CompositionLocal holding both scopes needed for shared element transitions.
 * Provided by [FolioSharedElementScope]; null on desktop or outside the host.
 */
internal val LocalSharedElementScopes = compositionLocalOf<SharedElementScopes?> { null }

/**
 * Provides the SharedTransitionScope to the CompositionLocal tree.
 *
 * Call from within SharedTransitionLayout's content lambda on Android.
 * On desktop, pass null and shared element transitions are skipped.
 */
@Composable
fun FolioSharedElementProvider(
    sharedTransitionScope: SharedTransitionScope?,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSharedTransitionScope provides sharedTransitionScope,
        content = content,
    )
}

/**
 * Publishes the pair of scopes a shared element needs.
 *
 * The [animatedVisibilityScope] is the navigation destination's own
 * `AnimatedContentScope` — the scope whose enter/exit transition tells the
 * shared element registry which copy of a key is arriving and which is
 * leaving. The SharedTransitionScope comes from [FolioSharedElementProvider]
 * higher up the tree, so a screen only has to hand over its own scope.
 *
 * With no SharedTransitionScope in composition (desktop), content renders
 * unchanged and every [sharedElementOrNoop] below is inert.
 */
@Composable
fun FolioSharedElementScope(
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val sts = LocalSharedTransitionScope.current
    if (sts == null) {
        content()
        return
    }
    val holder = remember(sts, animatedVisibilityScope) {
        SharedElementScopes(sts, animatedVisibilityScope)
    }
    CompositionLocalProvider(
        LocalSharedElementScopes provides holder,
        content = content,
    )
}

/**
 * Set while a screen swaps one layer of its own content out and another in — the
 * grid↔list↔compact cross, where the outgoing grid and the incoming list are both
 * composed for the length of the dissolve and both publish the same cover key under
 * the same `AnimatedVisibilityScope`. Two live copies of one key is a case the
 * shared-transition registry has no answer for: it can leave a cover stranded on the
 * layer that is fading out. So the modifier stands down until the cross ends.
 */
internal val LocalSharedElementsSuppressed = compositionLocalOf { false }

/**
 * Holds [LocalSharedElementsSuppressed] for [content]. Nested providers widen
 * rather than clear, so an inner screen cannot re-enable a morph its outer
 * container has just suspended.
 */
@Composable
fun FolioSharedElementsSuppressed(
    suppressed: Boolean,
    content: @Composable () -> Unit,
) {
    CompositionLocalProvider(
        LocalSharedElementsSuppressed provides
            (LocalSharedElementsSuppressed.current || suppressed),
        content = content,
    )
}

/**
 * Publishes a *content-level* morph scope: pair two states of the same screen with
 * a shared element, not just two destinations.
 *
 * [FolioSharedElementScope] hands over the navigation destination's own
 * `AnimatedContentScope`, which is the whole story while the only morphs are
 * shelf→detail. It is the wrong scope for a swap that happens *inside* one
 * destination — a selected paragraph becoming a quote card, a highlight blooming
 * into the annotations panel — because that swap has its own
 * `AnimatedVisibilityScope`, and the registry pairs the arriving copy with the
 * leaving one by that scope. Handing the nav destination's scope to both sides
 * makes them look like two different navigations rather than one object changing
 * shape, and the morph does not run.
 *
 * So this is the content-level counterpart: call it around an
 * `AnimatedContent`/`AnimatedVisibility` swap, and any
 * [sharedElementOrNoop]/[sharedTextOrNoop] inside it will pair with the copy on
 * the other side of *that* swap. It nests — an inner host overrides the nav
 * scope for its own subtree and everything outside it keeps morphing across
 * routes as before.
 *
 * No-op (content rendered unchanged) when there is no shared transition scope in
 * composition, which is the desktop case.
 *
 * @param animatedVisibilityScope the scope of the swap this content lives in.
 *        On the source and destination sides of one morph this must be the *same*
 *        scope — normally the `this` of a single `AnimatedContent` block, whose
 *        incoming and outgoing branches share it.
 */
@Composable
fun FolioContentMorphScope(
    animatedVisibilityScope: AnimatedVisibilityScope,
    content: @Composable () -> Unit,
) {
    val sts = LocalSharedTransitionScope.current
    if (sts == null) {
        content()
        return
    }
    val holder = remember(sts, animatedVisibilityScope) {
        SharedElementScopes(sts, animatedVisibilityScope)
    }
    CompositionLocalProvider(
        LocalSharedElementScopes provides holder,
        content = content,
    )
}

/**
 * A [FolioContentMorphScope] around a boolean reveal, for the common shape where
 * one layer replaces another in the same slot.
 *
 * The two branches of [content] share one synthetic scope, so a key published on
 * the `visible = false` side pairs with the same key on the `visible = true`
 * side. That is the highlight→quote-card case: the passage is on the page, the
 * reader taps highlight, and the card grows out of where the passage was.
 *
 * Deliberately *not* wired to [FolioTokens.motionMorph] here — the caller owns the
 * reveal's own timing, because a morph between two states of one screen usually
 * wants to start before the layer it replaces has gone.
 */
@Composable
fun FolioContentMorphHost(
    visible: Boolean,
    content: @Composable (Boolean) -> Unit,
) {
    androidx.compose.animation.AnimatedVisibility(visible = visible) {
        FolioContentMorphScope(animatedVisibilityScope = this) {
            content(visible)
        }
    }
}

/**
 * Applies a shared element transition to this Modifier, or returns the
 * receiver unchanged when no SharedTransitionScope/AnimatedVisibilityScope
 * pair is available (e.g. on desktop, or outside a navigation destination
 * that published its scope).
 *
 * Also inert while a swap suspends the morph and under reduce-motion, where the
 * spec asks for a standard fade and no shared element at all.
 *
 * [boundsTransform] defaults to [folioMorphBounds]; pass a different spec only
 * when a morph should *not* match the app's standard cover timing.
 *
 * [placeHolderSize] picks what the *placeholder* — the box the registry draws
 * while a morph is in flight — is sized to:
 *
 * - [SharedTransitionScope.PlaceHolderSize.animatedSize] (the API default) sizes
 *   it to the interpolated bounds. The box therefore moves and resizes smoothly,
 *   but whatever is drawn inside it keeps its own size, so for a morph between
 *   two *different* sizes the content only matches its box on the final frame —
 *   which is what a cover "jumping into place at the very end" looks like.
 * - [SharedTransitionScope.PlaceHolderSize.contentSize] sizes it to the content,
 *   so the artwork is always the size of the box around it.
 *
 * The default here is deliberately `animatedSize`, matching the API and the rest
 * of the app: where both ends of a morph use the same size token (the library
 * grid's and the detail header's `coverFeature`), there is no size change to
 * disagree about and the interpolated box is exactly right. Pass `contentSize`
 * on a pair that crosses two different tokens — Home's `coverAnchor` handing to
 * a detail header, say — so the cover resizes *with* its box rather than after it.
 */
@Composable
fun Modifier.sharedElementOrNoop(
    key: Any,
    boundsTransform: BoundsTransform = folioMorphBounds,
    placeHolderSize: SharedTransitionScope.PlaceHolderSize =
        SharedTransitionScope.PlaceHolderSize.animatedSize,
): Modifier {
    val enabled = rememberMorphAttachment()
    val scopes = LocalSharedElementScopes.current
    if (!enabled || scopes == null) return this
    return with(scopes.sharedTransitionScope) {
        val state = rememberSharedContentState(key)
        this@sharedElementOrNoop.sharedElement(
            state,
            scopes.animatedVisibilityScope,
            boundsTransform = boundsTransform,
            placeHolderSize = placeHolderSize,
        )
    }
}

/**
 * The text half of a morph: pair a label on the source screen with the same
 * label on the destination so the run of text travels with its cover.
 *
 * `sharedBounds` and not `sharedElement`, because the two copies are almost never
 * laid out at the same size — a grid tile's title is `labelMedium` over two lines,
 * a detail header's is `headlineSmall` over three. `sharedElement` interpolates the
 * *content* between those two states, which drags the glyphs through a scale and
 * makes the text look like it is being stretched. `sharedBounds` keeps both copies
 * composed and cross-fades between them while only the box is interpolated: the
 * text appears to settle into its new size instead of inflating into it. That
 * cross-fade is also why callers do not need a separate fade on the text.
 *
 * No-op under the same conditions as [sharedElementOrNoop].
 */
@Composable
fun Modifier.sharedTextOrNoop(
    key: Any,
    boundsTransform: BoundsTransform = folioMorphBounds,
): Modifier {
    val enabled = rememberMorphAttachment()
    val scopes = LocalSharedElementScopes.current
    if (!enabled || scopes == null) return this
    return with(scopes.sharedTransitionScope) {
        val state = rememberSharedContentState(key)
        this@sharedTextOrNoop.sharedBounds(
            sharedContentState = state,
            animatedVisibilityScope = scopes.animatedVisibilityScope,
            boundsTransform = boundsTransform,
        )
    }
}

/**
 * The single gate every morph attachment passes through: false when the scopes
 * are absent, when a same-route swap has suspended morphs, or under reduce-motion.
 *
 * [sharedElementOrNoop] and [sharedTextOrNoop] both call it so a cover and its
 * paired title can never disagree about whether a morph is running — text that
 * flew while its cover stayed put would be worse than no morph at all.
 */
@Composable
private fun rememberMorphAttachment(): Boolean {
    val motion = rememberMotionEnabled()
    val suppressed = LocalSharedElementsSuppressed.current
    return motion && !suppressed
}
