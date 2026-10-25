# Open instrument — Android 1.2.114

This pass responds to the quiet-cyberpunk work being too incremental and the
everyday reader controls being too bulky. It changes composition and occupied
area, while retaining the established optical material, preferences and reader
engines. The Reading desk comparison toggle still controls the experimental Home.

## Native changes

- Reading desk places the title before the cover, at a substantially larger type
  rank and aligned to the leading edge. The active cover occupies the leading
  field; the next two sit smaller in the upper trailing field. Progress and turn
  controls use the space below them. The existing continuous revolving gesture,
  cover keys, cache, accessibility actions and bounded release remain.
- Cover placement, scale and depth follow one shelf position. Reduced motion
  retains the same composition without perspective/settle animation. The caption
  uses the shelf-turn motion profile. Artwork strength remains at the approved .52.
- Signal now uses bundled variable Space Grotesk for display and Manrope for UI
  copy. The historical preference ID stays compatible. Other UI pairings and the
  reader's page fonts are unchanged. The new font is in the UI-only extraction
  list, with the upstream OFL notice; no network font is needed on the phone.
- Shared reading actions lose the decorative index and large padding. A softer
  asymmetric silhouette and a compact arrow well carry the identity. Library's
  featured jacket uses the related silhouette, larger title/progress and the same
  "In your hands" hierarchy. Existing panel-opacity/sheen/vividness mappings stay
  on the current material seams.
- Book and document top bars paint a 32dp strip within 48dp action targets, with
  20dp icons and smaller gaps. No hit target is reduced to the painted strip.
  Everyday strips use shallow light catches without raised bevels or cast shadows;
  settings panels keep their original relief. The user's sheen gain still applies.
  Soft strip outlines retain diffusion and a painted fallback; these tiny controls
  do not use the rounded lens path, which disappears when a capture source is absent.
- Book reader rail: 32dp painted width, 48dp centered target width; 152dp resting
  height for Search, Contents and Notes. Highlight and optional Echoes join only
  while a passage is selected. With all five actions it occupies 256dp instead of
  approximately 345dp. The foreground stays separate from material effects.
- Book/document progress loses excess vertical padding and the secondary chip.
  PDF mode, seek, rotate and reset-zoom share one row instead of two. Manga uses
  the same reader material and smaller floating strips in place of full-width
  black gradient bands. It keeps its page seek and action callbacks.
  Manga retains the narrow system-status contrast band for Android's light icons.
- Roomy settings sheets, opacity ownership, source ordering, page dimensions and
  book-loading readiness are not rewritten. No timer delays opening a book and
  no per-frame native-page resizing is introduced.

## Interactive study versus Android

`folio-open-instrument.html` is an updated in-conversation study. Its larger Home
composition and thinner reader controls inform this native pass. Its single
bottom surface that grows into tools, and its six covers spreading into a Library
grid, remain proposals. They are not claims about the shipped Android navigation.
The native app retains its existing shared cover transitions and centered reader
rail; replacing the navigation/control model is a separate interaction change.
The sample book text, calendar labels and collection in the study are fictional.

## Phone review checklist

Use Reading desk on Home and Signal as the UI typeface to see the full direction.

- [ ] Home title has a clearly larger hierarchy; active cover is offset, with the
      other covers and progress making deliberate use of the trailing space.
- [ ] Drag, rapidly reverse, and use both turn buttons. The front cover, caption,
      artwork and reading action agree on the selected title.
- [ ] Resume opens promptly. Returning and switching Home/Library retain the
      existing cover handoff without a new loading pause.
- [ ] Library's featured title, progress and action surfaces feel related to Home.
      Long titles and large text remain readable and usable.
- [ ] Book reader: resting rail is a short thin strip with three centered icons.
      Selecting text exposes Highlight and Echoes. Each action remains easy to tap.
- [ ] EPUB/DOCX/PDF/manga: top and bottom chrome leave more page visible. PDF mode,
      rotation, reset-zoom and seek remain reachable. Manga bookmark/notes/settings
      still work.
- [ ] Reader settings keeps its substantial, readable panel. Reader opacity, app
      panel opacity, sheen and vividness remain independent as before.
- [ ] Check light/dark, a long title, large text, landscape and reduced motion.

Verification and install evidence are recorded in `outputs/open-instrument-*`.

## Verified delivery

- Signed release 1.2.114, versionCode 168: `BUILD SUCCESSFUL`, 2026-10-21.
- 30 focused checks in 9 suites, zero failures/errors. Includes cover bounds,
  gesture/reduced-motion behavior, RTL/density alignment, selection-only rail
  actions, opacity fallback cases and unchanged settings-sheet behavior.
- Native previews reviewed at 320/393px, light/dark, with a large-text Home case.
  These are offscreen Compose renders, not phone captures or FPS measurements.
- Interactive study checked at 320/736px in light/dark, with selection, navigation,
  reading tools, search, highlighting and reduced motion; zero script errors.
- All three APK signatures and bundled Space Grotesk/font license verified. The
  phone's ARM64 APK is saved as `releases/Folio-1.2.114-arm64.apk`; the ARMv7 and
  universal variants remain in `D:/gbuild/androidApp/outputs/apk/release`.
- ARM64 installed through ADB and package version verified. App not launched or
  navigated, preserving the user's install-only preference. Phone interaction and
  hardware visual/performance checks remain manual checklist items above.

The build volume was nearly full. Automatic approval review blocked removing a
generated native-library cache, so no cleanup occurred. Packaging only the phone's
APK avoided duplicate variant copies; existing releases and source were preserved.
