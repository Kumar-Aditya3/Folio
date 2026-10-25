# Reading-room corrections

Release: **1.2.103 (157)**, built and installed via ADB on 2026-10-21.

## Corrected behavior

- **Where your time went:** books and manga now resolve to named rows from the
  same filtered 365-day session window. Reading format is part of the aggregation
  key, so identical ids in separate libraries cannot collide. Manga opens its own
  detail route. Rows beyond the eight-entry ranking contribute to “More titles”.
  Book-only summaries remain book-only. Manga read without favouriting still resolves by name and keeps its recorded
  time. Applicable manga/category/source/extension exclusions filter both totals
  and rows; favouriting itself never changes the total.
- **Last 7 days:** the plot has a fixed date/minute readout below it. The floating
  glass lens, arrows, reserved lens spacer and animated x/y layout are removed.
  Horizontal scrubbing changes discrete days; vertical scrolling keeps its gesture.
  Keyboard arrows and accessibility actions remain available. The graph does not
  paint an opaque paper rectangle inside its glass parent.
- **Continue reading:** the in-progress book queue is no longer capped at six.
  The desk previews three titles; the lower shelf retains every other in-progress
  title. Only the active centre is omitted, avoiding duplicate transition sources;
  the two side previews remain in the queue.
  Recent manga capacity increases to twelve. Side previews do not attach a shared transition source, so their lower-shelf
  counterparts can animate into details without ambiguity.
  Continuation covers grow to 112dp and the heading exposes the queue count.
- **Discover:** uniform 92dp covers and a quieter heading make it a secondary
  browsing shelf, smaller than Continue reading.
- **Card definition:** paper and reference surfaces gain a small tonal separation
  and a restrained directional hairline. Their soft shoulders and absence of cast
  shadows remain. No animated material loop is added.
- **Stats glass:** charts and the overture use a simple frosted data material over
  the actual registered room, through the existing Haze adapter. It has no thick
  ornamental bevel or shadow. The panel-opacity preference controls the material;
  100% opacity becomes a true solid and avoids unnecessary backdrop capture.
  Quotes/passages keep their matte paper material.

## Validation

The focused run passed **145 tests in 26 suites** and generated **64 native render variants**.

Regression checks cover mixed-format attribution, equal ids in separate libraries,
actual repository data and exclusions, a thirty-book Home queue, the three-cover
preview, graph height and pointer selection, and rendered pixel transmission at
both low and full panel opacity with blur enabled and disabled. Existing scope,
startup, settings, motion and optical-glass checks remain in the focused run.

Native variants include light/dark themes, 393px width and 320px at 140% text,
including a mixed manga/book allocation and a room-backed transparent Stats card.
Release and installation results are recorded in `outputs/reading-room-polish-*.json`.
Phone interaction remains install-only at the user's request.

## Recommended next direction

The strongest next step is a coherent editorial library rather than another
independent surface experiment. The primary journey should feel continuous:
Home shelf → title page → reader. Let the selected cover carry the lighting and
identity into the next screen, then let the light recede as the page becomes ready.
The existing shared-element keys and cached artwork provide the basis. Android's
[shared-element guidance](https://developer.android.com/develop/ui/compose/animation/shared-elements)
describes the visual connection between screens that this builds on.

Art direction should use a consistent type scale, deliberate shelf spacing,
palette-relative paper/ink, and recognizable cover objects. Keep one artwork
accent in focus; make glass adjustable and consistently defined across controls
and data panels. Preserve legibility and unobstructed information as design rules.

For UX, prioritize consistent cover-tap/Continue/long-press behavior across books
and manga, followed by a compact/comfortable shelf-density control. These changes
would give the existing rotating desk a coherent experience to belong to.

## Delivery

Both desktop compilation and the signed Android release build passed. All three APKs
match the existing signing certificate. The phone reports **1.2.103 (157)** after
`adb install -r`; the app was not launched or navigated. Packaged DEX verification
confirms the new graph readout and named-time remainder, with the old graph prompt absent.
