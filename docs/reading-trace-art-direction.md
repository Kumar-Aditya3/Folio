# Reading traces and nine material families

Android delivery target: 1.2.116 / 170. Build/install results are recorded in
outputs/reading-trace-* when verification finishes.

## Scope

The theme collection expands from seven to nine pairs. Existing app palette IDs,
reader preset IDs and theme-pack IDs remain valid. No library, saved passage or
reading history is migrated or rewritten. The existing seven pairs are reauthored
alongside the two additions; independent reader-page and custom-theme choices
remain supported.

| Pair | Material and color relationship |
| --- | --- |
| Classic / Charcoal | Achromatic silver, etched neutral glass |
| Paper / Ember | Vellum and amber, indigo and quiet violet counterpoints |
| Forest / Midnight | Mineral green, celadon, petrol, parchment time marks |
| Ocean / Abyss | Blue enamel, sea glass, amber time marks |
| Editorial / Crimson | Garnet, verdigris, parchment and cool violet |
| Blush / Vaporwave | Opal, rose, aqua and lavender |
| Acid Rave / Synthwave | Graphite, chartreuse, electric blue and warm amber |
| Porcelain / Cobalt | Ceramic white/slate, cobalt glaze and apricot |
| Linen / Copper | Warm stock, blue-grey glass, copper and pale mineral green |

Each face authors its own ground, surfaces, inks and role colors. Semantic hues
are fitted offline against each face's own surface; the emitted Kotlin table is
explicit. Functional roles are consistent across destinations: interaction,
time/progress, incoming/discovery, saved meaning, and streak. Active navigation
uses the interaction role instead of a different hue for every destination.

Artwork continues to light its neighborhood; the Home and book-detail reading
actions use the chosen theme's interaction color. Classic no longer receives the
previous forced teal Signal fallback. Reader-page primary ink remains a readable
neutral within its stock rather than inheriting a neon UI accent.

Material finish adds bounded reflected light and optional brush lines, without
another blur pass, opaque plane or idle animation. Different families have
different reflection spread, warmth and opalescent counter-light. Metallic sheen
still controls finish intensity; panel/reader opacity continues to own the base
material transparency.

## A record that can be followed

ReadingTraceRepository observes one shared set of bulk reads above Android
navigation. It does not query separately for every visible cover and does not
listen to the high-frequency reading-progress revision. Session and annotation
writes update their own revisions. Deleting or restoring a highlight updates its
trace; old unmeasured reading sessions are excluded.

- Actual session ranges form engraved contours and sitting-end ticks.
- Saved passages form chapter-position marks; notes distinguish their marks.
- Progress is the live reading fraction supplied by the surface displaying it.
- Home and the featured Library entry expose the trace and its passage list.
- Standard Library entries and cover fore-edges carry the same record.
- Book detail retains the trace between Library and reader.
- Reader progress uses those marks in the compact bottom instrument. A newly
  saved mark receives finite feedback; reduced-motion settings are respected.
- Following a saved passage uses the existing highlight deep link, not a second
  navigation/seek implementation.
- Echoes retains the selected source text above related passages. Its semantic
  result colors stay legible and stable within the selected theme.

The record is empty when no history exists. It is never synthesized from a book
title or a decorative random seed. Session contours are capped at 48 per drawn
trace, marks at 40, while totals and the passage list retain the full record.

Chapter word counts define the same bands as the reader's seek instrument.
Where a locator lacks a local fraction, its mark uses the chapter midpoint;
these are chapter locations, not claims about exact word offsets. The trace line
also shares a navigation key across its Home, Library, detail and reader nodes,
so existing enabled shared transitions can carry it between those surfaces.

## Allocation scope patch

"Where your time went" now admits only current library subjects that pass the
statistics exclusions. A resolved extension manga with inLibrary=false cannot
become a row. Its "Others" remainder uses the sum of eligible allocation entries
before the visible-row cap, rather than the broader reading-time total. Removed
books and excluded subjects cannot leak through that denominator. Broader
reading-time counters retain their existing scope; this patch targets the
requested library allocation.

Regression checks cover library add/remove, same IDs in both formats, book and
manga-source exclusions, and a nine-title allocation where only the ninth
eligible title belongs in the eight-row view's remainder.

### Source-only follow-up

After the earlier release build completed, the user requested no further build
or installation. These additional changes are therefore not in that APK:

- Manga "Most read" uses the main statistics pipeline's eligible, exclusion-filtered
  library feed before the mixed allocation's eight-row cap. Its window is explicitly
  labelled as the last 365 days.
- Manga aggregate snapshots observe manga/category/history and session revisions.
  The Android host retains one repository instance; exclusion changes replace the
  old collector and clear its stale snapshot while the replacement loads.
- Manga category revisions re-resolve exclusions even if the entry list is equal.
- Forecast models now receive the actual book cover path, as Home's models do.

`StatsLiveContentTest` adds live ranking, category/library removal, uncapped manga
ranking, cover-path propagation and summary-refresh regressions. These new tests
have not been executed, per the no-build request. The earlier 146-test pass predates
this follow-up.

## Statistics as a composition

Statistics now shares Home's open field, typographic scale and scroll-coupled
reveals. It no longer repeats a glass rectangle around every section.

- The opening week figure and goal dial sit directly in the room. A compact
  two-column ledger combines streak, seven actual activity marks and year totals.
- The weekly curve stays open and retains its compact tap/drag readout.
- The year calendar has no raised material or enclosing pane. Quiet outlined
  dates and softly cut activity marks use a stable time/streak ramp, rather than
  changing their meaning with cover artwork. Filter controls and the spoken
  activity summary remain available; tiny dates are not touch targets.
- Finish forecasts become a small cover shelf, carrying the same real reading
  contours as Home beside each title.
- The time allocation earns a bound glass ledger with a narrow leading edge.
  The radial reading fingerprint earns a rounded instrument, with supporting
  facts in a two-column engraving. These share the app's glass chemistry and
  transparency preferences. There is no extra fill or blur pass.
- Genre rhythms, passages and manga figures return to the open page. Saved
  passages retain their annotation rule, so they look different from numeric data.
  Categorical bars now draw from each face's authored discovery, annotation,
  time and streak inks; the legacy global red/yellow/green chart palette no longer
  overrides the theme.

Native review covers both faces of Crimson, Vaporwave and Synthwave, plus narrow
large-text Porcelain and Copper. The calendar test also checks that its actual
room color remains visible and that changing format changes the activity marks.

## Review targets

Check all eighteen faces, especially Vaporwave versus Synthwave and Crimson's
contrasting planes. Try a book with several sessions and saved passages, save a
new passage, return to Home/Library, open its trace and follow a passage. Repeat
with reduced motion and enlarged text. Phone interaction remains install-only
unless the user authorizes device UI checks.
