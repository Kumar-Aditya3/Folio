# Folio Android: complete reader UI/UX and visual direction

Research and proposal only. No reader implementation, build or installation in
this pass. The Stats follow-up remains source-only.

## Code audit

| Area | Current behavior | Design opportunity |
| --- | --- | --- |
| ReaderScreen / ReaderChrome | Separate top islands, left floating tools, bottom progress; a text selection reveals the whole chrome | A focused reading layout and contextual tools beside the selection |
| ReaderSidePanels / ReaderPanels | Five separately composed side panels; Contents 260dp, settings 280dp, annotations 300dp, Search/Echoes 320dp | One adaptive workspace with shared header, navigation, width and dismissal behavior |
| ReaderCss / PageLighting | Static lift/shade already exists; headings default to body ink; normalized mode neutralizes drop caps | Authored chapter composition and page stock, beyond another lighting effect |
| HtmlContentSurface.android | Android returns false for surface occlusion: reader tools/panels are Compose; page text remains a WebView | Design the actual Android Compose reader shell; style book content through both paged and continuous CSS paths |
| ReaderSearchPanel | Nearest-first in-book results, current-chapter marker, side panel | Preserve this strength and add reversible preview/return navigation |
| ReaderPanels / ReaderDialogs | Separate bookmark/highlight/note sections, per-row delete actions, note composer in a dialog | A chapter-organized annotation journal and anchored composition with quieter secondary actions |
| ReaderEchoesPanel | Selected source text above separate resonance-tinted result cards | Keep source and destination together as a passage exploration experience |
| ReaderSettingsPanel | Long 280dp inspector; scrollable theme list inside the panel; scope chips at top | A deliberately designed inspector with pinned scope, specimens and progressive disclosure |
| PageBlock / FolioReadingTrace | Seeking occurs during dragging | Preview a destination while dragging and commit once, with an explicit return origin |
| MangaReaderScreen / MangaReaderChrome | Opaque black stage, wider grouped chrome, separate settings/action/note dialogs | Same reader architecture as books, with a format-specific viewing stage |
| MangaPager / MangaReaderPosition | RTL/LTR, vertical paging, continuous webtoon, focal-point zoom compensation, chapter slots and prefetch | Preserve the mature reading mechanics; design around real page/chapter positions |
| Manga notes | Chapter note list shows page labels and delete actions; no row action that seeks to a note's page | Page-anchored notes, jump-back behavior, and visual page references |
| ReaderChapterChip / MangaPager | Book completion chip; manga end-of-chapters label and loading indicators | One coherent chapter-boundary and feedback vocabulary |

This is a source audit. Existing screenshots from earlier work are layout
references, not evidence of the latest phone state.

## The direction

A precise digital reading instrument built around literary typography. Modern
minimalist cyberpunk should be recognizable through composition, strong type
hierarchy, calibrated marks, purposeful color, concentrated light and direct
manipulation. The complete experience needs one design grammar.

### 1. A coherent reading layout

Use three clear states. Reading gives the text or image the viewport. A center tap
reveals a compact back/title/Tools arrangement and the progress instrument. An
active task opens the shared workspace. Text selection opens its own contextual
actions, rather than also revealing every navigation control.

Consolidate the book's always-expanded tool rail into the shared tool/navigation
architecture. Contents is accessible from the progress instrument and Tools;
search and saved marks have stable positions. Manga follows the same locations,
with format-appropriate commands. Preserve thin visible strips and generous touch
targets. Roomy settings remain intentional.

### 2. A typographic identity on the actual page

Author chapter openings: a genuine small chapter index, a composed title, a narrow
rule, deliberate whitespace and a strong opening paragraph. Use display typography
for the opening and restrained monospaced metadata for location. The user's chosen
reading font continues to carry prose. Preserve publisher typography in Original
mode and handle front matter without invented chapter numbering.

Use the same title hierarchy in Contents, navigation previews and chapter handoffs.
This is a visibly different page composition, not only a new heading color.

### 3. Material and shape have jobs

The reading field is matte paper or an optical dark surface. Commands use thin
satin/etched material. Workspaces use diffused glass with a defined edge. Saved
passages use an ink-and-paper treatment. Their geometries belong to one family:
softly squared controls, a generous leading corner on a workspace, restrained page
edges, and small round transient confirmations.

Re-author the existing page-light recipe per family: broad ceramic edge light,
vellum binding shade, graphite's precise neutral edge, opal's opposing margin hue.
Concentrate texture and reflection outside the prose. Retain the nine paired
themes, independent/custom page themes and the existing opacity/sheen/vividness
controls. A material recipe must not override the reader's preferences.

### 4. One workspace, multiple tools

Contents, Search, Marks, Echoes and Settings share one adaptive shell and header.
Switching tools changes its content in place. Use visible text labels as well as
icons. A stable layout replaces five independently sized drawers. On a narrow
phone keep a visible sliver of the reading context; wider windows can show more.

Contents becomes a readable chapter map with clear hierarchy, the current location,
saved-mark indicators and optional measured progress. Retain the existing nearest-
first search results. Settings gets a pinned scope control, quick typography/page
adjustments, a real type specimen, nine paired stocks and a secondary advanced
section. Preserve the current font-in-its-own-face previews.

### 5. Navigation that keeps a place safe

Expand the progress instrument into a temporary navigator. Dragging previews a
chapter label and passage for books, or a cached page thumbnail for manga. Commit
the destination on release. Keep an explicit origin/Return control after a search,
Contents, note or scrub jump. Previewing should not rewrite the reading position or
mark skipped pages as read.

This gives the bottom instrument a real interaction identity and avoids repeated
cross-chapter loads during one drag. RTL, keyboard/accessibility actions and the
existing semantic location restoration need first-class treatment.

### 6. A connected annotation experience

Selection actions appear beside the selected passage: Highlight, Note, Copy and
Echoes, with secondary actions tucked away. A saved mark becomes part of the
margin/progress record. The note composer retains its source passage and closes
back toward that mark. Organize the saved journal by chapter with passage-first
hierarchy, filters and quieter destructive actions.

For manga, connect each note to its actual page index and an optional cached page
reference. Tapping a note seeks to that page. Existing bookmarks are chapter-level;
the design must not pretend page bookmarks or measured page-range history exist.
Echoes should retain the source passage, show the related passage in context, and
provide a clear return to the original reading position.

### 7. Manga as a deliberately framed viewing experience

Give the uncovered viewport a restrained theme-derived matte and material edge,
using the fitted image bounds. Preserve the artwork's colors. An image that fills
the viewport owns that area completely. The stage can change personality through
its frame, chapter label, navigation instrument and notes.

Add an in-reader chapter map and a preview contact sheet using already available
page images. Keep RTL directionality, webtoon continuity and focal-point zoom.
Continuous chapters receive unobtrusive boundary cues in the existing gaps;
paged chapter changes can briefly expose the next title and page edge. Neither
requires a mandatory interstitial or a different image renderer.

### 8. Color and type form a syntax

| Element | Role |
| --- | --- |
| Prose and manga artwork | Reading ink / original artwork |
| Active control and focused tool | App theme interaction accent |
| Progress, chapter position, page ticks | Time/progress accent |
| Saved passage and note tools | Annotation accent; preserve chosen highlight colors |
| Quiet boundaries and secondary metadata | Neutral field inks |
| Problems requiring action | Error/status color |

Use three typographic voices: the chosen reading face for content, a composed
display face for chapter/workspace titles, and restrained tabular/monospaced type
for genuine location data. Tool labels remain plain language. Theme character
changes the ink relationships and material, while this hierarchy stays learnable.

### 9. Motion and feedback complete the identity

One choreography across both formats: controls settle onto the page, a tool opens
its workspace, a progress line unfolds into a navigator, and a saved mark lands
where its passage belongs. Use short, finite state transitions and direct finger-
tracking gestures. Keep the page still during sustained reading and respect the
existing motion/haptic preferences.

Bring save, sync, loading, empty and error states into this same language. Replace
the manga save-path toast with an actionable confirmation, give a loading chapter
clear local context, and make retry states belong to the reading stage. Preserve
the existing placeholder geometry, image cache and prefetch behavior.

## Implementation order after design approval

1. Shared reader layout, workspace shell, contextual selection tools and navigation
   preview/Return behavior. These establish a complete UI/UX change.
2. Chapter typography, material stocks, manga framing and the common color/type grammar.
3. Anchored annotations, chapter/page maps, coordinated feedback and motion polish.

Implement a coherent vertical slice in both formats, covering opening, reading,
navigating, annotating, adjusting and returning. Review it in light/dark themes,
large text, narrow widths, RTL, reduced motion and low/solid opacity. Do not migrate
the rendering engines or add a decorative framework to get this result.
