# Widgets and notifications

Android entry point: **More → Widgets & notifications**. The page renders the same
native RemoteViews used by the launcher and can request either home-screen widget.
Launchers without pinning support show instructions for their widget picker.

- **Continue Reading:** the most recently opened unfinished book, manga, or document.
  Cached cover art stays local; a tap opens the reader. Manga chapters follow story
  order even when their source lists newest first. Missing/deleted items fall back
  to the library.
- **Daily Rhythm:** today's minutes, the existing daily goal, and the trailing
  seven days. It uses the Statistics pipeline, including exclusions, rather than
  deriving a second set of reading totals.
- Both widgets use the current Folio palette with translucent glass gradients and
  fine highlights. Compact widths hide the cover to keep the title readable.
  Widgets refresh after app use, during reading (throttled), and through Android's
  30-minute widget update mechanism. Every bitmap update replaces the full view.

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

Verification: `ReadingSurfacePolicyTest` covers timing, deduplication, late-night
boundaries, goal arithmetic and manga order. `ReadingWidgetRenderTest` inflates
the actual launcher layouts in dark/light and compact/wide variants and writes
PNG review artifacts to the app's external `widget-review` directory.

Verified on an isolated Android 16 emulator: seven unit tests and three native
tests passed, including actual library/statistics loading, notification delivery
on separate channels, and sixteen light/dark, compact/wide, short/tall widget
renders. Review PNGs and the universal debug APK are in `outputs/widget-final/`
and `outputs/Folio-widgets-debug.apk`.
