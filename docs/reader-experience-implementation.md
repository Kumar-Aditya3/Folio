# Folio reader experience

Android implementation of the quiet, modern, minimalist cyberpunk reader direction.

## Visual hierarchy

The page stays still. Its artwork and selected reading face remain content; the surrounding
instruments use the app's typography, purposeful accent roles and a restrained optical finish.

- A single shallow command strip replaces the three separate title/action islands. Back, title,
  bookmark and Reader tools keep full touch targets without painting thick controls.
- Contents, Search, Saved, Echoes and Settings share one inset, softly squared workspace. Manga
  uses the same shell for Pages, Saved and Settings. The header and tool navigation stay pinned.
- Reader glass has diffusion and one etched edge, without brushed texture, grain, repeated ribs,
  ornamental bevels or cast shadows. It uses the current theme's neutral reference plane.
- Reader opacity controls the actual paint. Working panes use a slightly denser, monotonic coat for readable text; command strips use the direct value. Metallic sheen adjusts its restrained light catch;
  vividness continues through the shared theme reference colour. At full opacity the plane is solid.
  A native WebView cannot supply a Haze backdrop: book controls use a translucent painted fallback,
  while Compose manga pages can supply real diffusion. Hosted reader forms preserve that policy.
- Saved passages have their own ink and chapter hierarchy instead of another layer of cards.
  Selected highlight colours stay intact. Destructive actions remain visually secondary.
- Normalized books gain composed display headings matching the selected app typeface, a short rule, deliberate spacing and quiet
  opening paragraphs. No extra DOM nodes or invented chapter labels are inserted. Original and
  Hybrid keep their heading composition. The same CSS reaches paged and continuous paths.

## Interaction

- Selecting text opens a small contextual strip with Keep, Note, Copy and (when enabled) Echoes.
  Android no longer needs the persistent left rail or a full navigation reveal for selection.
- Progress movement previews locally; only releasing the pointer seeks. Preview cancellation makes
  no reading-position write. Accessibility progress actions still seek directly.
- Book previews identify the destination chapter and location. Book navigation retains an exact
  origin locator for Return after contents, search, saved-passage or scrub jumps.
- Manga previews and the page map reuse cached compressed page bytes, decoded at thumbnail size
  off the UI thread. Opening a map does not trigger source-image downloads. Uncached pages are
  honestly labelled and remain navigable.
- Manga notes can be edited and tapped to seek their actual page. Notes created across a continuous
  chapter boundary retain the long-pressed page's chapter, not merely the active chapter.
- Settings use an actual type specimen and readable miniature theme examples. Typeface choices
  continue to render their names in their own face. Book/global scope and override/reset behavior
  remain available.
- Motion uses the existing finite motion system and reduced-motion preferences. The workspace
  slides in as one object; switching tools changes content within its stable shell. No idle shader
  or animation clock is added to a reading page.

## Preservation and limits

The page engines, zoom ownership, RTL tap direction, stable page keys, placeholder geometry,
cover-to-first-paint handoff, measured reading traces, original artwork and saved-position model
remain in place. Manga's Return is within its active chapter; choosing another chapter uses the
existing chapter navigation. There is no fabricated manga reading-history trace.

APK packaging and installation are intentionally separate from this source and validation pass.
Native Compose review images and verification logs are produced under `shared/build/visual-review/reader-experience`
and `outputs/reader-workspace-*`.

## Discover controls

More → Manga → Discover offers As usual (default), Selected extensions and Hide Discover.
Package allowlists are persisted with global settings and retained across mode changes.
Empty selected lists deliberately produce no Discover results. Hidden and empty-selected modes
skip Discover browse requests and cached rows. Settings and exclusion changes cancel an in-flight
Discover fetch; cache identity includes the mode, selected packages and exclusion rules. Explicit
extension selection can discover from an unread source, while the default keeps its recent-reading
anchor. Missing/uninstalled selections remain editable in Settings and never fall back to an
unselected extension.

## More search

The More masthead opens a search rail using the same field as Library, with downward expansion,
matching finite motion, a pinned header, automatic keyboard focus and system-back dismissal.
Results match destination names and intent keywords, including Discover controls. Opening a result
keeps the query for the return journey; closing search clears it. Matching handles case, accents,
punctuation and multiple required words, and prefers title matches. No library/content search or
extension browse is triggered by settings search.

## Validation

Android production and unit-test sources compile successfully. The targeted regression run passed
146 shared tests and 2 Android settings-search tests, with no failures or skipped tests. Native
Compose previews cover all 18 palettes, narrow/large-type layouts and the manga workspace. These
are component renderings, not device captures. Results are recorded in
`outputs/reader-experience-final-verification.json`; the run log is
`outputs/reader-final-validation.log`. This pass did not package or install an APK.
