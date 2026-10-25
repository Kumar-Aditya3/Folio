# Folio reading-room materials

Follow-up corrections to Stats attribution/transparency, graph interaction and Home
shelf hierarchy are documented in [reading-room-polish.md](reading-room-polish.md).

Implementation date: 2026-10-21. Release: **1.2.102 (156)**, built and installed via ADB.

The artwork desk's visual language now extends through the main application.
It uses the existing Compose/Haze renderer, cover caches and motion preferences.
No schema migration or additional rendering dependency is introduced.

## Materials and forms

`FolioMaterials.kt` defines deliberately non-optical paper and reference finishes.
Paper has soft 16/22dp shoulders, no cast shadow, bounded static grain and optional fine page-edge
rules. Reference panels use a stable 24dp silhouette with a diffuse fill and no
perimeter stroke. Both follow the background and borrow restrained palette hue
without changing their target luminance. Accent rules are short and inset, rather
than full-height hard cuts. Matte interface shapes use gentler cubic shoulders; existing card/inset forms
soften to 20/14dp. Functional glass retains Haze-compatible rounded geometry and
printed covers retain their physical, nearly square corners. Shape-family seeds
and layout bounds stay stable while scrolling.

`FolioArtwork.kt` extracts the cached artwork renderer from Home without depending
on Home models or a carousel. Static title/feature scenes use a quieter strength
behind text, while Home retains its approved gesture-driven background strength.
Artwork observers never initiate another network request or decode.

`FolioActionDock.kt` provides the shared glass action, local press light, progress
and accessible disabled state. It honors reduced motion and glass capability
fallback; the panel-opacity setting controls its optical tint. Reading surfaces
remain matte when glass is enabled. Glass imports remain inside `Glass.kt`.

## Screen changes

- **Home:** the Reading desk branch uses open lower sections, a compact ledger,
  an arrivals cover fan, an editorial Discover spread and a paper weekly summary.
  Recommendations show actual shared-series/author reasons and retain the
  completed title as their source. Existing scope exclusions and cover-key
  deduplication remain active. Classic Home remains available through its switch.
- **Book detail:** a responsive open title scene, shared reading dock, solid
  metadata panel and matte description. Large text stacks the identity below
  the cover. Existing cover/title transitions and reader callbacks remain paired.
- **Manga detail:** the same physical-cover/title scene and primary reading dock.
  The measured Continue-to-masthead handoff keeps its compact header control.
  Library and download actions use a reference panel; chapter rows are solid
  rather than individual optical panes. Selection and download controls remain.
- **Library:** book and manga featured entries use artwork compositions around
  physical covers. The book grid adds an actual filtered title count. Existing
  grid/list/compact modes, selection, filter rails and anchored menus remain.
- **Search:** shared Library/global result rendering uses cover/source rows and
  expandable paper passages. One result expands at a time, with an explicit
  reading action that preserves the original hit, including semantic locator.
- **Quotes:** paper passages carry source covers, highlight accents and expandable
  note/actions. The grid keeps one expanded quote. Compact rows and manga notes
  receive the same matte material. Tag and related-passage actions remain wired.
- **Stats:** paper chart sections and a quiet reference overture. A glass date
  lens reads actual daily minutes; horizontal dragging selects a day, buttons
  animate the lens, and keyboard/accessibility actions provide the same access.
  The small heatmap remains an overview with its existing spoken summary.
- **Reader panels:** annotation rows and book/document typography previews gain
  the paper treatment. Actual EPUB, manga and document page rendering, native
  surface handling and reserved-chrome fallbacks remain intact.
- **Settings:** grouped controls use solid reference surfaces. Themes, Transparency
  and Animations gain a real native material preview with a finite tap-triggered
  cover lift; desktop/fallback settings receive the same preview. The opacity
  label now describes glass panels rather than promising translucent paper.

## Backlog projection correction

The broader Home checks exposed an existing merge defect: the quick last-read
manga identity discarded the fully resolved row's backlog projection. The new
merge retains fresh progress and identity while carrying over projection fields
only for the same chapter/read timestamp. It never applies an old projection to
a different chapter or delays the fast first state with additional queries.
The initial manga-progress stream now also allows the book layout to settle when
no manga history is available; a wired history still resolves the actual last-read
row before the first hero emission, preserving the manga-first launch behavior.

## Review and limits

The final focused run passed **124 tests across 23 suites** and generated **56
native render variants**. Native Compose previews cover the main compositions in both light and dark
themes, 393px width, and 320px with 140% text, including bright and missing covers,
expanded passages, chapters and a scrolled Home. Behavior checks cover action
dispatch/disabled state, paper remaining matte with optics enabled, built-in
palette body contrast, date hit regions, actual recommendation reasons and
the manga projection merge. Existing startup, scope, preference and motion checks
are included in the focused test run.

Successful test, APK-signature/version and installation records are written to
`outputs/reading-room-test-verification.json`,
`outputs/reading-room-release-verification.json` and
`outputs/reading-room-device-install.json`. Representative renders are under
`outputs/reading-room-*.png`.

Phone interaction checks remain opted out at the user's request: installation
is authorized, launching or navigating the app for live visual/performance checks
is not part of this delivery. No phone frame-time or battery improvement is claimed.

Optional later refinements from the research, such as per-date title attribution
and a collection-specific cover collage, require their own data/interaction work.
Daily totals and recommendation reasons in this release use existing actual data.

Release verification completed: all three APK signatures match the existing release certificate.
The connected phone reports version **1.2.102 (156)** after `adb install -r`.
The release build passed its R8 startup-constructor guards. The app was not launched
or navigated during this installation.
