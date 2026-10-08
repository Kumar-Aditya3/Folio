# Plan — match the reference silk

Measured on the reference's own size (298×511) so the two are directly comparable.
Tools: `ref_analyse.py`, `ribbon_metrics.py`, `thread_width.py`, `ref_compare.py`.

## What the reference actually is

Four to five **broad soft ribbons**, each carrying 20–40 very fine threads at 4–5 px spacing.
The threads drift across their ribbon slowly, so they cross each other at shallow angles and read
as a fine shimmer rather than a lattice. Ribbons bunch into groups and cross at ~40°, and each
ribbon's body is a wide glow with a soft edge — never a defined band.

## Measured gaps

| measure | reference | render now | gap |
|---|---|---|---|
| mean strand angle | 39.2° | 43.8° | 11% too steep |
| ribbons crossing a slice (28% thr) | 3.3 | 7.2 | **2.2× too many** |
| ribbon body width (10% thr) | 17 px (p75 32) | 11 px (p75 23.5) | **1.5× too narrow** |
| thread spacing | 4–5 px | 4 px | ok |
| thread fan inside a ribbon | +25…+45° | +35…+55° | right width, shifted 10° |
| near-black | 73% | 71% | ok |
| clearly lit | 9% | 15% | 1.7× too many |
| hue families | 120–210° 68%, 300–360° 12% | matches | ok |

## The three real problems

**1. Too many separate ribbons.** Six bundles, each spanning the full width, evenly staggered — so
every vertical slice crosses all six. The reference's ribbons converge into groups, so a slice
crosses three or four. Fixing this is a *composition* change, not a tuning change.

**2. Ribbon bodies too narrow.** 11 px against 17 px. The reference's ribbons carry a much wider
soft halo around the thread core; mine end sooner. This is the body/ambient blur, not the geometry.

**3. Threads cross too steeply.** `turns` was raised to 1.3–1.9, which snaps each thread across its
ribbon and builds a visible basket weave. The reference's threads drift across slowly: many threads,
shallow crossings.

## Phases

**Phase 1 — ribbon count and bunching (composition).**
Give every bundle an explicit **x-range** so not all span the frame, and make pairs share a corridor
for part of their length before separating. Target: 3–4 ribbons per slice at 28% threshold, down
from 7.2. This is the change that will move the render most.

**Phase 2 — ribbon body (rendering).**
Widen the body/ambient blur so the low-threshold ribbon width goes 11 px → 17 px, and pull the core
alpha down so clearly-lit goes 15% → 9%. The thread core must stay; only the halo grows.

**Phase 3 — angle.**
Trim the spans ~11% so the mean screen angle is 39.2°. Reduce `turns` to ~0.5–0.7 so the thread fan
centres on the ribbon instead of sitting 10° steeper.

**Phase 4 — animation.**
The 10 rendered frames barely differ. Raise the macro amplitude and lower its wave number so the
composition visibly opens, crosses and relaxes over ~12 s, then re-run the topology check
(each pair must still cross once).

**Phase 5 — verify.**
Re-run all four measurement scripts and the 10-frame render, and report the table above again.

## Success

The gap column above should be within ~10% on every row, and the 10 frames should show a clearly
evolving composition.
