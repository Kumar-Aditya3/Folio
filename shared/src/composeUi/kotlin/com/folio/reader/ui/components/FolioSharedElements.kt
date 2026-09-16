@file:OptIn(ExperimentalSharedTransitionApi::class)

package com.folio.reader.ui.components

import androidx.compose.animation.AnimatedVisibilityScope
import androidx.compose.animation.ExperimentalSharedTransitionApi
import androidx.compose.animation.SharedTransitionScope
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.compositionLocalOf
import androidx.compose.runtime.remember
import androidx.compose.ui.Modifier
import com.folio.reader.ui.theme.rememberMotionEnabled

/**
 * Shared-element keys for covers that morph between list/grid and detail/reader screens.
 *
 * Keys are stable and unique per item: a book cover uses `"book_cover:${bookId}"`, a
 * manga cover uses `"manga_cover:${mangaId}"`. Both the source (list/grid thumbnail)
 * and the destination (detail/reader cover) must carry the same key for the
 * SharedElement transition to animate between them.
 */
object FolioSharedKeys {
    fun bookCover(bookId: String) = "book_cover:$bookId"
    fun mangaCover(mangaId: String) = "manga_cover:$mangaId"
    fun documentCover(documentId: String) = "document_cover:$documentId"
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
 * Applies a shared element transition to this Modifier, or returns the
 * receiver unchanged when no SharedTransitionScope/AnimatedVisibilityScope
 * pair is available (e.g. on desktop, or outside a navigation destination
 * that published its scope).
 *
 * Also inert while a swap suspends the morph (§13.6) and under reduce-motion,
 * where the spec asks for a standard fade and no shared element at all.
 */
@Composable
fun Modifier.sharedElementOrNoop(
    key: Any,
): Modifier {
    val motion = rememberMotionEnabled()
    val suppressed = LocalSharedElementsSuppressed.current
    val scopes = LocalSharedElementScopes.current
    if (!motion || suppressed || scopes == null) return this
    return with(scopes.sharedTransitionScope) {
        val state = rememberSharedContentState(key)
        this@sharedElementOrNoop.sharedElement(state, scopes.animatedVisibilityScope)
    }
}
