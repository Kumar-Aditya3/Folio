# Widgets and notifications

Android entry point: **More → Widgets & notifications**. The page renders the same
native RemoteViews used by the launcher and can request all three home-screen widgets.
Launchers without pinning support show instructions for their widget picker.

- **Continue Reading:** the most recently opened unfinished book, manga, or document.
  Cached cover art stays local; a tap opens the reader. Manga chapters follow story
  order even when their source lists newest first. Missing/deleted items fall back
  to the library.
- **Daily Rhythm:** today's minutes, the existing daily goal, and the trailing
  seven days. It uses the Statistics pipeline, including exclusions, rather than
  deriving a second set of reading totals.
- **Audiobook:** the current narrated book or the last saved listening checkpoint,
  with circular cover artwork and a whole-book progress ring. Play/pause and
  previous/next passage controls use the existing narration service. A stale pause
  or skip action cannot affect another book. Missing models open narration setup;
  adding a widget never downloads a model or starts playback.
- All widgets share one translucent painted-glass renderer using Folio's opacity
  and glass lighting tokens, directional edge, and deterministic grain. They follow
  the current palette, dedicated widget opacity, metallic sheen and liquid-glass
  effects preferences. Maximum opacity stays solid. Launcher RemoteViews cannot
  capture the app's Compose backdrop or run live Haze refraction, so this is the
  painted material fallback, with wallpaper showing through the pane.
  **More → Transparency → Home-screen widgets** controls all three widgets from
  10% to 100% opacity, independently of app bars and reader controls. The default
  is 70%; resetting the transparency controls restores it. Changes refresh installed
  widgets automatically after the settings save debounce.
  The audiobook preview/default height is 132dp (previously 212dp in the preview
  and 184dp in the launcher), and it can shrink to 112dp while retaining 48dp
  playback targets. Short layouts omit the eyebrow and chapter before crowding
  the title and status. Compact reading widgets retain a smaller cover.
  Widgets refresh after app use, during reading (throttled), and through Android's
  30-minute widget update mechanism. Playback state and palette changes refresh
  immediately after a short debounce. Every bitmap update replaces the full view.

Reading reminders are off by default. Enabling them requests notification
permission when necessary. A local WorkManager check considers the chosen time
and a bounded 90-minute window, skips days with a reading session, and posts at
most one reminder per local date. Late-night windows stop at midnight. Android
may defer background work; this is an invitation, not an exact alarm.

Manga checks keep one summary per run with chapter navigation. Downloads retain
their foreground progress notification and post one quiet completion/failure
summary for the queue. Notification channels can be managed independently in
Android settings; reminders and download results default to low importance.
Notification content is marked private for lock-screen presentation.

## Verification

- `ReadingSurfacePolicyTest` covers reminder timing, deduplication, late-night
  boundaries, goal totals, and manga chapter order.
- `ReadingWidgetRenderTest` inflates the actual launcher layouts across palette,
  size, text scale, and opacity settings. Preview images are generated under
  the app's external `widget-modern-review` directory.
- `AudiobookWidgetStateTest` covers empty, missing-model, paused, playing,
  preparing, error, and finished states. It also verifies that a stale pause
  action cannot affect another book.
