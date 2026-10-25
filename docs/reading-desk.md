# Reading desk Home

Enable **More → Themes → Reading desk Home** to compare the experimental Home
composition with the classic layout. The global `homeReadingDesk` preference
defaults to false, so existing installations keep classic Home. The setting
uses the existing settings store, serialization and patch merge; it is not a
per-book override and does not require a database migration.

The opening composition is a [revolving reading shelf](revolving-shelf.md).
It contains the current book or manga and up to two upcoming titles. The selected
cover's primary action resumes the book or its last-read manga chapter; without
a manga chapter it opens details. The existing statistics, exclusions, remaining
shelves, discovery and week sections remain in the same lazy list below it.

Scrolling reveals subsequent sections as they cross the viewport above the
navigation capsule. Scroll offsets are read
inside `graphicsLayer`, so they update layer transforms and opacity without
recomposing the entire Home screen on each scroll delta. There is no new
continuous animation clock or image decoding path. The room background, Haze
overlays and scroll quality policy remain active; the revolving shelf has its
own isolated cover-colored optical scene.

The app's entry easing and intensity control the reveal. Reduced motion and
disabled entry motion render the sections fully visible, remove cover travel
and make the scroll shortcut instant. Content height can grow for long titles
and larger text rather than clipping into a fixed hero rectangle.

## Verification

`ReadingDeskTest` checks the legacy default, database persistence, isolated
settings patches and viewport reveal boundaries. `HomeStatsScopeTest` checks
switching both ways on the same Home view model while retaining the shelf.
`ReadingDeskPreviewTest` renders both compositions in light and dark themes.

The current shelf's focused suite also covers turn physics, interruption and
the motion preference matrix. See [revolving shelf verification](revolving-shelf.md#verification)
for those checks. The earlier comparison-switch device records remain in
`outputs/reading-desk-device-startup.json` and `outputs/reading-desk-device-ui.json`.

The desktop render checks also exposed Material 3 desktop 1.9.0 resolving
datetime 0.7.1 at runtime while Folio compiles its models against 0.6.1. Desktop
uses the official [0.6 compatibility runtime](https://github.com/Kotlin/kotlinx-datetime#deprecation-of-instant)
to retain the legacy Instant/Clock classes. It is runtime-only because the 0.7
source APIs change date arithmetic return types. Android retains its existing
datetime dependency.
