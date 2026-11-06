# Windows reader

The desktop EPUB reader uses Chromium for the page and Compose for the controls.
The top and bottom strips reserve fixed space, so opening controls does not change
the page's geometry. Reader tools use an owned window above the native browser.

## Controls

- Left / Right, Page Up / Page Down, and Space / Shift+Space navigate the page.
- The bottom strip has previous and next buttons and a book position scrubber.
  Scrubbing previews locally and seeks once on release.
- A click in an empty outside gutter turns a page. A horizontal drag starting in
  that gutter also turns a page. Dragging prose remains text selection.
- The mouse wheel turns pages in paginated mode and scrolls in continuous mode.
- F toggles focus mode. Reading clicks and selections do not toggle the controls.
- Ctrl+F opens search. Escape closes reader tools before leaving the reader.

## Pagination and resource use

Windows pagination packs laid-out blocks into viewport-sized surfaces. One surface
paints at rest; a transition briefly paints the incoming and outgoing surfaces.
The whole chapter is never promoted into a single animated compositor layer.
Hidden pages remain available to passage lookup without being painted.

Original paragraph indices are retained across page grouping, continuation slices,
and typography changes. Selection, passage jumps, and highlights use those indices.
Minimizing the owner window disables page input and pauses the browser-side work.
Desktop graphics retain at most 64 MiB of cached Skia textures per context. The
browser's own allocations and the JVM remain separate from that limit.
Android retains its existing pagination and touch interaction paths.

## Verification

The regular desktop reader tests cover keyboard and pointer arbitration, cancelled
scrubs, disabled navigation, fixed viewport geometry, large text, and inspector tools.
An optional browser test exercises real layout in long chapters with one and two
columns, including reflow, passage jumps, highlight anchoring, resizing, saved
positions, and reduced motion:

```powershell
$env:FOLIO_READER_BROWSER = 'C:\Program Files\Google\Chrome\Application\chrome.exe'
.\gradlew.bat :shared:desktopTest --tests '*DesktopPaginationBrowserTest'
```

It uses the installed browser's DevTools protocol and Node 22 or newer; no npm
packages are required. These rendering bounds are a resource constraint, not a
claim about total application memory or a device-wide benchmark.
