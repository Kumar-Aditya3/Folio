# Reader art direction: quiet modern minimalist cyberpunk

Proposal only. The latest request authorizes the Stats fixes and discussion;
reader appearance, reader engines and reader gestures are unchanged in this pass.

## What the code already does

- `ReaderScreen.kt` and `MangaReaderScreen.kt` deliberately occlude the app field.
  Books render their own page in a WebView; manga renders over an opaque black stage.
  Animating the global app field behind those surfaces would add cost without
  producing a visible result.
- `PageLighting.kt` already generates a static CSS lift/shade for the book page.
  `ReaderCss.kt` handles paged frames and continuous chapter shadow roots separately.
  A more visible reading identity should extend this existing path.
- Book chrome defaults to `LocalFolioReaderCalmGlass`, suppressing much of the
  material's hue. Both readers use `ReaderChromeGroup`, although their overall
  arrangements differ. Its visual strip is already inset inside larger touch targets.
- Books have the new actual-session/saved-passage trace. Manga still uses `PageBlock`.
  Manga has page-indexed notes and chapter bookmarks; it does not yet have equivalent
  measured per-page session ranges. Those cannot be invented for visual decoration.

## Recommended direction

### 1. Make chapter openings the strongest identity moment

Give normalized book chapters a deliberate typographic opening: a genuine chapter
index when known, a strongly composed title, a fine theme-colored rule and a spacious
first paragraph. The body keeps the user's chosen reading face. Signal typography
belongs to the chapter label and instrument numerals. Preserve publisher formatting
in Original mode and avoid manufacturing chapter numbers for front matter.

The title's accent settles once at chapter arrival. Ordinary lines of prose stay
still. The same opening treatment appears in Contents and chapter-jump previews,
so the reader learns one hierarchy.

### 2. Give the page a material rather than a wallpaper

Re-author the current static page-light recipe around the nine families. Examples:
ceramic has a broad cool edge, vellum a warm binding shade, graphite a precise
neutral rim, opal a restrained opposing hue at the margin. Texture and stronger
reflection belong outside the prose column. Keep ink contrast, the reading theme,
custom stocks and the OLED-black option authoritative.

The visible change comes from distinct chapter composition and edges, rather than
continually moving light beneath words. Apply styling through both existing CSS
paths, including continuous chapter shadow roots. Paged layout, saved positions,
selection, footnotes and restoration need explicit verification before shipping.

### 3. One compact control instrument for both formats

Use the same title treatment, slim material, etched active state and motion grammar
in book and manga chrome. Retain the 48dp touch targets with thin painted strips.
Shallow glass carries transient commands; denser frosted material carries settings;
annotations use a quieter paper surface. Opacity, sheen and vividness remain wired
to the current settings, with the established distinction between controls and panels.

Place status on a deliberate axis: chapter/title above, progress below, active tool
at the margin. The coordinate frame appears with controls and withdraws with them.
Avoid a permanent surrounding HUD or a wider toolbar.

### 4. Marginalia makes reading history visible

Connect saved passages, notes, Contents and the bottom trace. A saved mark appears
at its actual place; selecting it opens its passage/note, with a finite transition
back to the same mark when closed. Preserve user-selected highlight colors; the
theme's annotation role identifies the surrounding tools and indicators.

Use existing content anchors and clipping boundaries. Margin marks must not change
text wrapping, cover words, steal page-turn gestures or turn tiny marks into tiny
touch targets. Larger accessible actions remain available in the annotation panel.

### 5. Manga becomes a framed viewing stage

Keep the artwork's pixels and color intact. Put the theme's material into uncovered
gutters and the chapter boundary, using only the actual fitted image bounds. The
field disappears where art fills the viewport; it never becomes a wash over panels.

Author the manga progress instrument from real page positions and page notes, with
chapter bookmarks identified at chapter level. Mirror it for RTL. A chapter handoff
can briefly reveal the next chapter's title and a narrow material edge, then give
the page the full stage. Zoom, webtoon continuity, prefetching and page-turn latency
take priority over decorative effects.

## Suggested first implementation

Start with the shared compact control kit, genuinely authored chapter openings and
the manga gutter/chapter stage. These three should make the new identity apparent
on the first reading session. Add anchored marginalia next, once both renderer paths
and RTL geometry have been verified. No new visual framework is required.
