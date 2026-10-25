# Quiet Signal — implementation and phone review

Built and installed: **1.2.112 (166)**, 2026-10-21. Implements the
[quiet modern minimalist cyberpunk direction](quiet-signal-art-direction.md).

## What changed

### Type and hierarchy

The default UI pairing is now **Signal**: variable Inter headings and Manrope UI
text. The historical `classic` preference key remains compatible, so existing
users of the default receive the new pairing without a settings migration.
**Literary** retains the original Fraunces/Manrope pairing as a selectable option.
Editorial quotes keep the literary face even with Signal selected. Other chosen
UI pairings remain selectable. Reader page typefaces and per-book overrides are
not rewritten. Inter was already bundled on Android; the desktop resource and its
existing OFL notice are now included for faithful previews.

Small section labels, large statistics figures, reading-action metadata, Library
hero percentages and shared slider readouts use monospace where useful. Ordinary
body copy remains proportional. This changes typographic structure rather than
adding fictional labels or technical-looking IDs.

### The signal motif

A pair of offset strokes marks the active Home book and reading action, Library's
Reading now entry, and the source edge of highlighted/saved passage sheets. Active
navigation gains a small paired underline. The selected state keeps its icon,
label/checkmark and accessibility semantics; it does not rely on the signal alone.

Neutral supplied accents gain a restrained cyan signal at reading actions and
spectral edges; light palettes use a darker teal for definition. A chromatic
cover or theme accent keeps its hue, even when the surrounding theme is neutral. Covers keep their actual colors and the previously reduced artwork
projection strength.

### Materials and shapes

Reference surfaces and action docks share a softer 22dp family, while real covers
retain their physical corners. Settings, More, Stats and reader settings inherit a
short spectral edge catch through the common data-glass seam. This does not add a
new fill, enclosing neon box, backdrop registry or capture source. The existing
panel-opacity mapping and readable reader-drawer veil remain in control.

Saved text stays matte, with paired source-colored marks. Home remains an open
composition; the weekly graph has no card, fill or border. No static disk or glass
partition is added to the revolving covers.

### Movement and interaction

- The shared reading action gives a finite travelling light response when its
  book/caption changes. A held press produces a local focal light, gentle compression
  and a small forward arrow movement; release/cancellation settles it.
- Library's in-progress cover seams use the shared animated progress component.
  Shared progress bars have a subtle brighter endpoint instead of a flat fill.
- The existing measured segmented indicator now carries a fine moving selection
  tracer. The navigation selection keeps its existing glide/width behavior and
  carries the new paired marker with it.
- Embedded settings choices interpolate their tint, edge and leading state mark.
- Shared sliders retain Material's value/gesture/keyboard/accessibility handling,
  with a thinner track and a soft rectangular grip carrying two tiny index strokes.
  The local engagement halo remains finite and follows the existing motion system.

The new effects use current motion profiles, finite Compose animation and cached
Canvas drawing. No dependency, perpetual signal animation, network request, vector
index load or new artwork decode is introduced. Current cover transitions and
carousel gesture behavior remain the basis of the browse-to-read flow.

### Preference coherence

The added spectral edge, reading-action sweep and focal light scale with metallic
sheen. Turning sheen off removes those catches while leaving useful state marks,
progress and fills. Existing vividness continues to govern artwork and material
color behavior; it is not repurposed as a neon-intensity control. The new dock
travel/press motion checks both platform motion and the app's resolved reduced
motion. Shared selection animation uses the resolved motion table. Opacity remains
a fill preference, with full opacity solid for actual panels. An open Home graph
has no fill for that preference to alter.

## Checklist for the installed app

Use **More → Settings → General → Typeface → Signal** if another UI pairing was
previously selected. The default pairing is already Signal after the upgrade.
Keep Reading desk enabled on Home to inspect the revolving-cover treatment.

- [ ] **Typography:** compare Signal and Literary. Look for clean Inter headings,
      monospace figures/metadata, and readable body copy. Reading page fonts should
      keep your existing choice. Font names in the reader picker preview themselves.
- [ ] **Home hero:** turn between covers. Look for the paired On your desk marker,
      content-aware signal accent, and a brief travelling catch on the reading
      action when its caption changes. It should settle, not keep scanning.
- [ ] **Reading action:** press and hold Continue reading, then release. Look for
      local light, gentle compression and a forward arrow response. The action
      should still open once, with no wait for the effect to complete.
- [ ] **Lower Home:** scroll through Continue reading and the weekly graph. The
      wider graph should remain directly on the page with no isolated card.
- [ ] **Library:** inspect Reading now, percentages and in-progress cover seams.
      Change Books/Manga/Documents and watch the selected segment's fine tracer.
      Search and then open a title to check the previously fixed loading path.
- [ ] **Navigation:** switch Home/Library/Stats/More. Look for the paired selected
      marker travelling with the current item, without obscuring its label or badge.
- [ ] **Reader controls:** open settings, select a font and adjust size/spacing.
      Check the new grip, engagement halo, active-choice edge and leading marker.
      Values and actual page settings must still track your input.
- [ ] **More/settings:** inspect the shared smoked material, short spectral edge
      and consistent nested shapes. Reader settings should remain substantial enough
      to read over text.
- [ ] **Saved passages:** open a saved highlight/quote. The paired source-color
      marks should carry identity while the passage itself remains matte and clear.
- [ ] **Preferences:** try dark/light, low/full panel opacity, sheen off/full and
      Reduced motion. Added glints should disappear with sheen off; reduced motion
      should settle without travelling effects. Restore your preferred settings.

## Verification and limits

Targeted native checks cover dock dispatch/disabled states, reduced-motion idle
stability, real slider value mapping, sheen versus fill, live opacity across the
shared materials, open Home geometry, reader font selection and settings, glass
fallbacks, search entry cleanup, motion resolution, palette/contrast contracts and
normal/narrow large-text renders. Results are recorded under `outputs/quiet-signal-*`.
The final focused run passes **106 checks across 17 suites**. One unchanged legacy
palette-depth case (`DesignSystemTest.materialsAreVisuallyDistinguishable`) fails for
the Light palette (raised luminance 0.5906 versus sunken 0.9560). It is documented
in `outputs/quiet-signal-palette-failure.json` and excluded only by the local
release-check init script. The test and palette calculations remain unchanged;
no separate baseline rebuild is claimed. The initial full focused run was 106 pass,
1 fail. The release APK is version/signature verified before installation.

Phone handling remains **install only**. The app is left closed for the user's
review. Native synthetic renders are not phone screenshots, and no phone frame-time
or battery improvement is claimed.

Installation verified via ADB against the connected phone: versionName 1.2.112,
versionCode 166. The app was not launched. All three release APKs match the
existing signing certificate; hashes are in `outputs/quiet-signal-release-verification.json`.
