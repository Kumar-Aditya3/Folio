package com.folio.reader.ui.theme

import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.ui.graphics.Shape
import androidx.compose.ui.unit.dp

/**
 * Folio's shape language.
 *
 * The pre-redesign app had exactly three radii (20/14/10) and used the 20 for
 * everything, so a hero, a statistic and a settings group were the same
 * silhouette at three sizes. Shape now carries meaning:
 *
 *  - [hero] is **asymmetric** — a wide sweep at the top-start where the cover
 *    enters, a tight corner at the bottom-end where the text ends. It cannot be
 *    mistaken for a card even blurred to 8px (§12.1 Rule 13's benchmark).
 *  - [plate] is nearly square: book covers are printed objects, not UI chrome.
 *    A 12dp radius on a cover reads as a widget; 3dp reads as paper.
 *  - [inset] is tighter than [card] because embedded things read smaller than
 *    the surface holding them.
 *  - [pill] / [chip] are for controls only. If it is a pill, it is tappable.
 *
 * Nothing here is a token alias: each value exists because a different class of
 * object needs a different silhouette.
 */
object FolioShapes {
    /** Hero surfaces: asymmetric, generous at the leading edge. */
    val hero: Shape = RoundedCornerShape(
        topStart = 34.dp,
        topEnd = 20.dp,
        bottomEnd = 34.dp,
        bottomStart = 8.dp,
    )

    /** Hero variant used when a cover bleeds off the leading edge. */
    val heroBleed: Shape = RoundedCornerShape(
        topStart = 0.dp,
        topEnd = 26.dp,
        bottomEnd = 26.dp,
        bottomStart = 0.dp,
    )

    /** Standard grouped content. Slightly tighter than the old 20dp. */
    val card: Shape = RoundedCornerShape(18.dp)

    /** Embedded/sunken panels inside another surface. */
    val inset: Shape = RoundedCornerShape(12.dp)

    /** A printed object: book and manga covers. */
    val plate: Shape = RoundedCornerShape(
        topStart = 2.dp,
        topEnd = 5.dp,
        bottomEnd = 5.dp,
        bottomStart = 2.dp,
    )

    /** Small plate for list-scale covers, where 5dp would read as a blob. */
    val plateSmall: Shape = RoundedCornerShape(
        topStart = 1.dp,
        topEnd = 3.dp,
        bottomEnd = 3.dp,
        bottomStart = 1.dp,
    )

    /** Controls: chips, segmented items, filter pills. */
    val pill: Shape = CircleShape

    /** Compact chips whose label must not look like a button. */
    val chip: Shape = RoundedCornerShape(9.dp)

    /** Full-width sections that meet the screen edge on one side. */
    val edgeStart: Shape = RoundedCornerShape(topEnd = 20.dp, bottomEnd = 20.dp)

    /** Sheets and panels anchored to the bottom of the window. */
    val sheet: Shape = RoundedCornerShape(topStart = 28.dp, topEnd = 28.dp)

    /** The floating navigation capsule. */
    val nav: Shape = RoundedCornerShape(26.dp)

    /** A quote/callout block: flat leading edge for the rule, round trailing. */
    val callout: Shape = RoundedCornerShape(
        topStart = 2.dp,
        topEnd = 14.dp,
        bottomEnd = 14.dp,
        bottomStart = 2.dp,
    )
}
