# Reading experience

The EPUB reader on Android and Windows shares soundscape settings, card layouts,
reading presets, return reminders, reading targets, reflections and review schedules.
Existing manga, document readers, widgets and notification work stays independent.

## Entry points

- **Reader tools → Reading settings:** presets; Text, Page and Comfort; Soundscape;
  Reading time and the optional Chapter reflections switch.
- Soundscape choices are **Off, Rain, Café, Fireplace**. Selecting a scene plays it;
  restoring a book or applying a preset prepares it paused. Optional secondary audio,
  independent levels, device master volume, responsive atmosphere and a sleep timer
  live here. There is no floating player or media notification.
- **Select a passage → Export:** opens the same composer used by Export on saved
  highlights, notes and quote-browser entries. Export does not save a highlight.
  The native selection bridge preserves the full selection rather than its former
  500-character excerpt.
- **Saved → Select for export:** choose a sequence, reorder by dragging or Up/Down,
  and preview all cards, including overflow. The Quotes screen also supports selection.
- **Saved / Book detail → Export reading journal:** choose chapters, writing,
  reflections, optional reading summary and ruled space; select A4/Letter and
  Editorial/Ink saver. Android hands off to Print / Save PDF; Windows saves a PDF
  or opens the native print flow. Cancelling never reports a successful export.
- **Saved → Leave myself a reminder:** keeps a normal editable note and its
  reading location. After 24 hours away, a return card offers context and navigation
  to the saved place without automatically moving the restored reader.
- **Review later** schedules saved highlights/notes. Home shows up to three due
  items; Revisit includes upcoming items. Remember, Again, Tomorrow and Remove act
  on the schedule and preserve the underlying annotation.

## Rendering and motion

Editorial, Atmosphere and Marginalia cards share one deterministic Canvas renderer
for both preview and PNG output. Sizes are 1080×1080, 1080×1350 and 1080×1920.
Long passages first fit one card by tightening margins/leading and scaling quote
type from 54 down to 30 pixels (personal notes stay at least 26 pixels). Only text
that cannot fit at that readable floor continues at word/grapheme boundaries;
source text is never abbreviated. Associated notes share a card when the combined
content fits. Source text and personal writing remain labelled separately.

Each template has its own material composition: Editorial uses an engraved
bookplate border and printer's ornament; Atmosphere uses a theme-derived colour
field, contour lines and layered paper; Marginalia uses a ruled notebook page,
margin line, ribbon and folded corner. Seeded paper grain stays identical in the
preview and export. Attribution wraps in a measured footer above an independent
branding row containing the actual launcher mark. Neither long book metadata nor
the passage can intrude into the icon's reserved space.
Quote text and ornaments follow the current app palette, including custom themes;
quote ink is checked against the actual paper at a 4.5:1 contrast floor. The Quotes
browser uses the same theme-coloured ink in both grid and list views. Ink-friendly
export remains neutral and independent of the current palette.

Exports use the current approved bundled font pairing (Signal, Literary or Soft),
with Literary as the fallback. Reader-imported fonts are not embedded. Journals
embed the bundled OFL Fraunces and Manrope faces and retain selectable text.

New named motion entries cover ink settling, export reveal, template transitions,
sound-control expansion, bookmark ribbons and paper reveals. Reduced motion uses
stable surfaces/fades. Sound response is confined to peripheral light and edges;
its visual clock stops when paused, muted, hidden by reader tools or disabled.

## Lifecycle and persistence

Audio is intentionally foreground-only. Leaving the reader, backgrounding Folio,
locking the screen, Android focus loss or headphone disconnection pauses output.
Returning never automatically resumes it. Sleep timers measure produced playback
samples, preserve the remaining duration while paused, and fade over ten seconds.
Off clears the countdown and closes the output. Reading-target time is independent
of sound and never writes to the reading engagement clock or statistics.

One shared PCM mixer feeds Android AudioTrack or Windows Java Sound. Paused audio
waits without polling; scene changes crossfade over 400 ms. The three local asset
loops and their preparation/licences are documented in [soundscapes.md](soundscapes.md).
The mixer exclusively owns device I/O, clip buffers and loop positions. UI commands
only update a short state lock; loading/writing/closing audio never hold it. PCM
buffers and indexed gains are reused instead of allocating lists/maps per sample.
Sound level drags update the mix live and persist once on release. Audio and
companion preferences are excluded from native page styling, and atmosphere
signals invalidate drawing without recomposing the controls or reading page.

Persistence deliberately uses the existing transactional settings row rather than
introducing a parallel schema: defaulted `soundscape` fields participate in existing
book snapshots/override resets, and typed `ReadingExperienceData` collections store
presets, return contexts, reflection-note links and schedules. Existing JSON decodes
with silent defaults. Atomic field patches preserve unrelated writes; database
settings revisions keep the UI current after restore/sync.

Backup v2 already contains global and per-book settings, so the new records travel
with it without a format break. Merge unions companion records and retains the
newest return context; replace adopts the restored state. Existing optional settings
sync carries companion records; annotation content is omitted when annotation sync
is disabled, and return positions also require position sync. Master volume is a
device-only raw setting; playback state, loop positions, active timers and generated
files are never synced or restored.

Deleting books/highlights/notes removes dependent companion references. Reflection
invitations require at least two active minutes and 100 credited words in a chapter,
are limited to one per session, and appear as a quiet action rather than interrupting
reading. Reflections are normal chapter notes with session metadata.

## Verification

`ReadingExperienceTest` covers defaults, settings scope, concurrent writes,
persistence, schedule intervals, deletion cleanup, merge, Unicode pagination,
journal escaping, reading estimates, foreground playback and real asset constraints.
`BeautifulExportsRenderTest` writes all template/format/theme combinations and
multilingual pages to `shared/build/visual-review/beautiful-exports`.
`ReadingJournalPdfTest` uses an already-installed Windows JCEF runtime to validate
real PDFs, text extraction, paper dimensions and rendered pages; it skips platforms
without that runtime instead of downloading one for tests.

Build and validation commands:

```powershell
.\gradlew.bat :shared:desktopTest :androidApp:assembleDebug :desktopApp:compileKotlin --max-workers=1
.\gradlew.bat :shared:desktopTest --tests '*ReadingExperienceTest' --tests '*BeautifulExportsRenderTest' --tests '*ReadingJournalPdfTest'
```

Android device checks must additionally verify audio focus/headphone handling,
screen-lock pause, share delivery, print cancellation and a sustained reading session.
