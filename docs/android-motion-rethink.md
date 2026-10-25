# Android reader rail and the motion rethink

Release **1.2.113 (167)**, 2026-10-21. Installed through ADB; app not launched.

## Rail correction delivered

`ReaderFloatingRail` now paints its glass on a separate background sibling. The
foreground actions use explicit full-width 56 × 48dp hit cells, with centered
24dp glyphs. This removes dependence on Material IconButton's implicit geometry
and keeps material effects out of the foreground icon chain. Selection gating,
Echoes, highlighting, search, contents, annotations and vertical scrolling remain.

The shared native alignment harness checks all five icon boxes against the actual
rail center in LTR/RTL and density 1/1.5. The desktop baseline was already centered;
this was not a reproduction of the phone's visual symptom. The phone was in another
app, so it was neither navigated nor captured. Sixteen focused checks passed, and
the Android release was built, signature/version verified and installed.
Evidence: `outputs/reader-rail-*`.

## Rethink: one interaction, one changing composition

The previous pass was too incremental: different surfaces gained accents, but
nothing established a strong relationship between selection, content and control.
The new direction is to coordinate meaningful geometry and timing across a journey.
The studies below are proposals, not movements shipped in 1.2.113.

**Linked depth** is the recommended direction. Selecting a cover changes the cover
stack, title, ambient position and reading control together. Opening it carries
that same cover into the reader's header identity; the title follows it and the
reading control moves into the footer. The page arrives within that composition.
Tools grow from the selected rail item into an attached control plane. Going back
reverses the journey rather than replacing it with another unrelated dissolve.

**Reading instrument** is a denser alternative: an asymmetric cover stack beside a
larger title, chapter context and excerpt, with the same browse-to-read continuity.
It gives the unused hero space a job and feels more like a personal instrument than
a centered gallery. At narrow sizes the other covers tuck behind the main one,
leaving the title column clear.

Both studies use a more characterful technical grotesk for interface hierarchy,
monospace for secondary positions and proportional reading text. The aesthetic
comes from proportion, surfaces and continuous movement; repeated decorative marks
are no longer the main identity mechanism. Content retains its actual colors.

The interactive study is stored in the thread visualization directory as
`folio-linked-motion.html`; its local interactions are sample content. Checks at
736px and 320px exercise cover selection, entering the reader, opening/closing
Contents, returning to the desk and centered 44px-or-larger rail targets. Screenshots
and the review JSON are local QA artifacts, not phone screenshots or native
performance measurements.

## Android implementation boundaries for the next pass

- Use the current cover shared-element identity and measured source/target bounds.
  A single progress/transition state coordinates cover, label, control and field.
- Start loading immediately. Hold the existing cover plate until the actual page
  readiness callback; animation never adds an artificial book-open delay.
- Keep Android WebView/document surface dimensions stable during the handoff.
  Avoid per-frame page reflow; the illustrative page movement in the study is not
  a requirement to transform or resize live reading content every frame.
- The active rail action owns the tool-panel origin. Keep keyboard focus, dismissal,
  scroll state and opacity in the current in-window overlay system.
- Gesture feedback is direct during a drag and uses a bounded settle on release.
  Reduced motion retains the same spatial hierarchy with short fades/state changes.
- Retain the approved subdued artwork, real glass capture, readable reader veil,
  open lower Home and unboxed weekly graph. Do not change the glass source ordering.
- Validate the complete Android browse/open/tool/back loop before wider migration;
  no new renderer or app-wide ornamental sweep is needed to test the direction.
