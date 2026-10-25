# Revolving reading shelf

The **Reading desk Home** experiment now presents the first three distinct
reading titles as a revolving shelf. Its comparison switch remains at
**More → Themes → Reading desk Home**; classic Home remains the default.

Horizontal dragging controls the angle directly. Release velocity projects a
short destination, then the app's `ReadingShelfTurn` motion profile settles the
plates with a damped spring. A new drag cancels the previous animation; repeated
arrow taps accumulate against the destination. There is no automatic rotation
or idle animation job. One title stays centered without unnecessary controls;
two titles form a bounded pair; three wrap continuously.

The foreground title drives its caption, progress, Continue action, details,
shared-cover transition and Home's ambient tint. Its selection survives ordinary
recomposition and configuration changes; a changed most-recent title resets the
desk to the new reading anchor. Book and manga IDs are distinguished. Covers
already on the desk are omitted from the lower Continue shelf to avoid duplicate
shared-element registrations. Remaining titles, discovery and reading statistics
still reveal on vertical scroll.

Perspective projection and depth ordering update during placement, with transforms
on cached cover layers. Lighting reads the same angle during drawing. The rest
of Home only observes selection changes, not every drag delta. Covers use the
existing Folio and manga loaders. Manga accent sampling observes their bitmap
cache and never initiates a new network request or image decode.

The cover plates have no glass sleeves, separators, wings, or floor. An enlarged,
softened crop of each already-loaded cover is projected across the hero, fading
into the page at all four edges. Artwork weights follow the carousel's continuous
circular coordinate, including the last-to-first wrap, with a smaller horizontal
translation than the covers. Generated typographic covers use an abstract jacket
composition while artwork is unavailable. This background follows the existing
ambient-color preference. All bitmap observers are bounded and cache-only: they
never request another download or decode.

Glass belongs to one functional reading dock: Continue reading, chapter and
progress share one large touch target below the caption. A local Haze registry
contains only the sibling artwork source, so the dock refracts that artwork
without capturing itself or the page containing it. The app's global registry
still captures the complete page for navigation chrome. The dock uses a transparent
base with a legibility tint and an 84% optical blend; capability-off fallback
uses a readable surface fill. The rim follows the palette and selected accent.
Touch feedback scales the dock slightly and draws a brief light at the actual
local press point. Reduced motion removes this response.

Artwork transforms and feathering read the angle in layers/drawing, not in
composition. Only the two nearest artwork layers are visible during a turn, and
there is no clock after settling. The shelf's existing workload budget also
scopes the dock's optics. No performance improvement is claimed without phone
measurements.

Reduced motion or a disabled morph channel removes perspective and animated
settling while retaining cover selection, arrow controls and reading actions.
Changing that preference mid-turn snaps to the nearest title. Leaving the scene
cancels its animation. The shelf also exposes selected-state semantics, previous
and next accessibility actions, and keyboard navigation.

## Verification

`RevolvingShelfTest` exercises perspective throughout a revolution, identity
deduplication, wraparound, bounded velocity projection, rapid turns, animation
interruption, small libraries and immediate motion. `MotionSchemaTest` applies
the app's preference/reduced-motion matrix to the new profile. Actual Compose
Home renders cover light/dark themes and one-, two-, and three-cover shelves at
320px width. Existing Home scope, preference persistence and glass tests remain
part of the focused validation.

`ReadingDockGlassTest` checks that the dock's source registry is isolated from
its parent page, that native optics retain the artwork's color structure, and
that pixels outside the control remain unchanged. Both themes and capability
paths are exercised. The geometry suite checks artwork weights across wrapping
and small libraries. Native Compose renders include actual cached image fixtures,
light/dark themes, 320px and 393px widths, and 140% type at the narrow width.

Release **1.2.101 (155)** passed **65 focused checks** and the minified Android
release build. ARM64, ARMv7 and universal APKs were verified against the existing
signing certificate. The ARM64 release was installed successfully with
`adb install -r`; the installed package reports version 155 / 1.2.101. Records are in
`outputs/artwork-desk-test-verification.json`,
`outputs/artwork-desk-release-verification.json`, and
`outputs/artwork-desk-device-install.json`. The phone
is installation-only at the user's request; no live device interaction checks
are claimed.
