# Extending the artwork reading desk across Folio

Research date: 2026-10-20. Baseline: installed release 1.2.101 (155).
This document records the source audit and design direction. The subsequent
implementation is described in [reading-room.md](reading-room.md), including the
delivered scope and remaining optional refinements. Phone performance has not
been measured for that implementation.

## Direction

Make Folio feel like a personal reading room. Artwork gives a place its character;
covers are objects; passages and reference information sit on quiet paper; glass
belongs to controls above that content. The balance changes with the task:
browsing is expressive, choosing a title creates focus, and reading becomes calm.

The signature should be continuity of a book's identity across places. A cover
arrives from Home or Library; its artwork opens into the detail background; the
reading control takes over; the artwork recedes as the actual page becomes ready.
Existing cover transitions already handle part of this journey. The proposed
addition is coordinated environment and action continuity around them.

## What the code already supports

| Existing implementation | What it makes possible |
| --- | --- |
| `ui/home/ReadingDeskArtwork.kt` | Cached artwork projection, feathered edges and gesture-driven crossfade. It currently depends on Home's item and carousel types. |
| `ui/home/ReadingDeskHero.kt` | Caption timing and a functional glass reading dock, including local press feedback. The dock is currently private to Home. |
| `ui/components/Glass.kt` | The single Haze seam, capability fallback, and a local artwork registry for the dock. |
| `ui/components/FolioSharedElements.kt` and `nav/FolioNavHost.kt` | Existing cover and text transitions, in-content morph helpers, and tab-specific behavior. |
| `ui/theme/FolioAtmosphere.kt`, `MainActivity.kt` | A shared theme field, cover tint, lighting, room source and reader occlusion policy. |
| `ui/components/FolioCoverPlate.kt`, `ui/manga/MangaCovers.kt`, `FolioBinding.kt` | Physical cover treatment, bounded bitmap caches and authored fallback bindings. |
| `ui/components/FolioChrome.kt`, `FolioChromeMaterial.kt` | Collapsing mastheads, folding rails and common navigation material. |
| `ui/components/FolioOverlayHost.kt` | In-window dialogs and menus; the backdrop does not need a separate native window. |
| `ui/theme/Motion.kt`, `EntryMotion.kt`, `FolioGlassWorkload.kt` | Existing motion channels, reduced-motion handling and cheaper optical rendering during interaction. |

The next work is primarily composition, material hierarchy and interactions.
Another rendering engine or general animation library is not justified by this audit.

## Shape and material system

Shapes and materials are central to the proposal, rather than a finishing pass.
The current code already has `FolioShapes` and seeded `FolioShapeFamily` variants,
plus surface-depth inheritance. The strongest improvement is to make the families
visually and behaviorally distinct, and to reduce the number of enclosing surfaces.

| Role | Form | Material and light | Appropriate places |
| --- | --- | --- | --- |
| Printed object | Tall rectangle, 1–5dp asymmetric corners, a narrow spine | Artwork or existing bookcloth binding; tight contact shadow and a restrained edge catch | Covers in Home, Library and detail |
| Reading surface | Near-square sheet; a flat leading edge or a small offset page edge | Matte theme-relative paper, almost invisible static grain, broad soft shadow; no optical refraction | Quotes, expanded search excerpts, journal-like Home details |
| Functional overlay | Soft rounded dock, capsule, or top-rounded sheet | Transmitted artwork, controlled blur, thin directional highlight, local touch response | Continue, filters, contextual actions, timeline date lens |
| Reference surface | Restrained 12–18dp corners, inset alignment | Quiet solid or soft porcelain-like finish, shallow occlusion, little gloss | Metadata, settings groups, chapter grouping |
| Editorial scene | Open composition; occasional asymmetric edge aligned with content | Artwork and atmospheric light feather into the theme field; no enclosing shiny silhouette | Home lead story, Library feature, detail title page |

Paper need not be beige, and dark themes should not become sepia. Derive its
lightness and hue from the user's palette. Likewise, the soft solid is a visual
finish, not a simulated ceramic object. Distinction comes from diffuse versus
specular response, edge treatment and shadows, rather than obvious textures.

Use the existing cloth weave on generated covers, not on every list or background.
Paper grain should be static, low contrast and absent from the actual reader page
unless the user chooses a paper effect. Glass grain should not be mistaken for
paper texture. Keep highlights directional and proportional to elevation.

The specific shape signature I would pursue is the **book spine and open edge**:
a flat leading edge, a narrow vertical accent, and a softer trailing edge on
passage/source compositions. The accent can carry a source book's color or a
highlight's real color. Reserve rounder shapes for manipulable controls. A
chapter label can sit on a small tab-like edge, but essential text stays away
from any clipped or decorative corner.

Seeded shape variation is already available. Use it sparingly on isolated hero
compositions; give repeated peer cards a stable shared silhouette so lists remain
orderly. A content sheet should still look like a sheet in a grayscale silhouette
test. Nested shapes should follow the outer curve and padding instead of being
several unrelated rounded rectangles.

Shape changes should explain an interaction: a filter capsule opening into an
anchored sheet, a reading control compacting into the masthead, or a source
excerpt expanding into a passage sheet. Keep in-flow clipping geometry stable
during scrolling; the existing shape code explicitly separates static container
geometry from decorative animated paths. Custom continuous-corner glass would
also require verifying that the Haze optics and clip use the same outline;
current rounded shapes are a safer first implementation.

This material hierarchy also needs explicit migration: `folioRaised` currently
can promote a top-level in-flow pane to optical glass when the preference allows
it. Applying a paper tint to that modifier would not create matte paper. A paper
surface needs its own deliberately non-optical treatment, with existing depth
inheritance retained for anything nested inside it.

A companion interactive material study illustrates these proposed distinctions
and lets paper corners, glass roundness and texture be compared. It is a concept
study, not a native-app screenshot or proof of Haze behavior. Native effects and
contrast still need the existing Compose render checks and authorized device review.

## 1. Finish Home as an editorial reading desk

**Observed:** `HomeScreen.kt` wraps the ledger, Continue, New chapters, Discover
and Because you finished in `HomeGlassCard`. The children have different purposes
but inherit essentially the same large container. `HomeWeekWell.kt` adds another
raised full-width treatment. Scroll reveals already exist in `ReadingDesk.kt`.

**Proposal:** Give the lower page a rhythm of objects, lists and open space.

- **Today and streak:** a compact reading ledger directly under the hero's fading
  atmosphere. Keep the actual goal and streak values, progress ring and exclusions
  entry; use a baseline and a fine rule rather than a large enclosing pane.
- **Continue:** freestanding covers with title and progress at their feet. A small
  depth response on pickup connects these to the hero without making another
  rotating carousel. Keep the existing exclusion of the three desk titles.
- **New chapters:** a compact arrivals strip. A short fan of cover miniatures
  introduces the group; each readable row retains its own title and chapter count.
  The fan supplements the rows rather than replacing their touch targets.
- **Discover:** an editorial spread with one larger lead cover and smaller neighbors.
  A bounded artwork wash supports the focused item. Source name remains explicit.
  Use settled selection for the wash; every visible thumbnail must not recolor Home.
- **Because you finished:** visually connect the completed title to its suggestions.
  Display an honest reason such as "Same series" or "By the same author" beside
  each suggestion. The matching information exists in `HomeViewModel.kt`; the UI
  state currently supplies only the completed title and candidate list, so a small
  explicit reason model is required. Do not imply semantic/AI recommendations.
- **This week:** a quiet closing chart on the page, with a clear route to Stats.
  The current sparkline and totals are reusable. Reveal the whole composition
  together; avoid independent entrance animations for every number and cover.

This is the best next experiment: it changes the parts immediately below the
approved hero and proves that the language works for varied content.

## 2. Turn book and manga details into the same title-opening experience

**Observed:** `BookDetailHeader.kt` puts the cover/title inside a raised, tinted,
rimmed hero. `MangaDetailScreen.kt` uses a smaller cover wrapped in `glassPanel`,
followed by metadata and actions. Manga already has a measured Continue-action
handoff into the collapsing top bar (`resumeMorph`).

**Proposal:** Continue the selected artwork into a feathered title-page background.
Let the physical cover and typography sit freely in that environment. Reuse the
Home reading dock for Start/Continue and progress. In manga detail, preserve the
existing handoff into the masthead while changing the material and composition;
do not add a second floating Continue control. Reuse the physical manga plate
instead of framing the raw cover image in glass.

The chapter list, synopsis and metadata should have quiet reading surfaces.
Chapters remain information-dense and selectable; the primary reading action gets
the expressive glass treatment. On entering a reader, artwork fades with the
existing cover/first-paint handoff. Do not add a second cover flight.

## 3. Make Library a collection, with useful density

**Observed:** Books already have grid/list/compact modes, selection, collection
chips and an in-progress featured entry. Grid cells already animate placement.
`FeaturedShelfEntry` is a raised pane with a themed rim. `LibraryScreen.kt` carefully
freezes rail measurements during search and preserves per-mode header heights.

**Proposal:** Keep the efficient shelves; redesign the featured entry as an open
artwork composition. A selected collection can have a restrained header assembled
from a few cached covers, with its actual name/count. The grid below uses consistent
cover shadows, typography and quieter progress seams, rather than more material.

The shared glass filter rail can open an anchored filter/sort surface that is
visually connected to its control. Selection should transform the existing action
area into a bulk-action tray. Preserve rail sizing and scroll position, and keep
compact mode compact. Avoid per-cell blur, automatic rotating shelves, and
background changes driven by whichever item happens to cross the viewport center.

## 4. Give search results and quotes an identity

**Observed:** `SearchScreen.kt` delegates to the shared `BookSearchResultsList`,
which also serves Library search. It largely uses Material `ListItem` rows with
chapter/snippet text. Quote cards use `FolioCallout` and a nested note surface.
`QuoteDisplayItem` includes the source book and chapter. Highlight-to-note morph
keys already exist; an HTML passage itself is not a Compose shared element.

**Proposal:** Search becomes a passage-first composition: readable excerpt, a
small source cover, chapter/location and highlighted match. A focused result can
expand its excerpt in place while a compact contextual action surface offers
opening it. Retain clear Books/Content/Annotations scope and honest empty/loading
states. Change the shared result renderer, so Library search stays consistent.

Quotes become collected pages: a small book/spine marker, generous quotation
type, source attribution, and the highlight's own accent. Expansion reveals the
note and actions together. Glass belongs to the contextual action tray. A faint
source-cover treatment is appropriate for one expanded quote, not every card in
a long list. Preserve the existing Compose-to-Compose note morph and use a normal
handoff into native reader content.

## 5. Make Stats touchable through the data

**Observed:** `StatisticsScreen.kt` wraps most sections in `StatsGlassCard`.
`StatsWeekChart.kt` already renders a smooth weekly curve. `StatsCharts.kt` deliberately
removed tiny per-cell heatmap selection. `StatDay` is date plus minutes;
`heatmapBooks` and `heatmapManga` are separate aggregate minute series, not lists
of titles read on each date.

**Proposal:** A larger weekly timeline supports a glass date/value lens while
scrubbing. It shows real minutes and a clear selected date, with previous/next
controls for keyboard and accessibility. The surrounding chart is drawn on the
page. Changing the date updates one summary area instead of animating all charts.
Keep the small heatmap an overview rather than restoring tiny touch targets.

An optional "What you read that day" cover ribbon would be a useful second step,
but requires a scoped session-to-title query. It cannot be implemented honestly
from the current aggregate lists. Charts keep consistent scale and accurate
values during transitions; lighting and animation must not exaggerate activity.

## 6. Bring the readers and settings into the language selectively

**Observed:** EPUB reader already has floating islands, side actions, a cover
handoff and a first-paint dissolve. Manga and document readers have their own
content/overlay constraints and all readers signal field occlusion. Desktop's
embedded HTML surface can paint over Compose overlays. Settings has category
navigation and a live preview for Text and Page, rather than all appearance controls.

**Proposal:** Reader pages inherit the calm typography and paper side of the
language. Make the contents, annotations and progress surfaces feel related to
the Home dock when invoked. Manga/PDF artwork can remain the backdrop for their
controls where capture works. Keep the actual page and its colors untouched.
Validate native-surface capture before promising refracted EPUB text; preserve
desktop's reserved-chrome fallback.

Appearance settings become a small workbench: the same real cover, paragraph and
reading control demonstrate palette, glass and motion changes in place. A tap
previews motion; no demonstration loops need to run indefinitely. Retain theme
identity and existing preferences rather than giving every screen a separate style.

## Shared implementation changes, when approved

1. Extract the artwork renderer and dock from Home into small reusable components.
   Keep carousel weighting in Home; other screens should not depend on
   `ReadingNowItem` or instantiate a carousel just to display one artwork source.
2. Reuse book/manga bitmap caches through bounded observers. Add a document adapter
   only where an existing thumbnail is available. Do not add another loader.
3. Introduce an open editorial section treatment alongside existing raised/sunken
   surfaces. Migrate chosen screens explicitly; changing `folioRaised` globally
   would also alter unrelated dialogs, readers and selection states.
4. Keep local artwork-source registries separate from containing page captures.
   Global navigation continues sampling the actual page; a local dock samples its
   sibling artwork. Exiting routes must not leak their retained content into the
   incoming route. Keep Haze imports in `Glass.kt`.
5. Coordinate the environment with existing cover/text keys and motion profiles.
   Preserve duplicate-key suppression, tab clipping, cancellation and first-paint
   behavior. Budget one prominent artwork scene per viewport; no per-cell optics.

## Suggested delivery sequence and review

1. Lower Home plus shared renderer/dock extraction.
2. Book and manga detail, including the existing resume/masthead handoff.
3. Library's featured composition and action/filter surfaces.
4. Search and Quotes as passage-focused views.
5. Interactive Stats and appearance previews; targeted reader controls last.

Each slice should make a complete visible improvement before proceeding. Review
both themes, bright/monochrome/missing artwork, book-only/manga-only libraries,
320px width, large text, reduced motion, glass disabled, rapid Back and interrupted
transitions. Measure frames and memory on the phone when live checks are authorized;
current desktop renders and tests cannot establish phone smoothness or battery cost.

## External design checks

- [Apple: Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/)
  describes glass as the floating functional layer and warns against applying it
  throughout content. This supports the material hierarchy, not copying Apple's UI.
- [Android: Shared element transitions](https://developer.android.com/develop/ui/compose/animation/shared-elements)
  explains cross-screen continuity for matching content. Folio already implements
  this foundation; proposed work extends the surrounding scene rather than replacing it.
- [Haze 2.0](https://chrisbanes.me/posts/haze-2.0.0/) documents source-backed
  refraction, lighting and performance controls. Those capabilities are already
  available in Folio. They do not, by themselves, determine a successful layout.
