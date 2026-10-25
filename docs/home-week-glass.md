# Home weekly graph material correction

Release 1.2.110 (164), 2026-10-21.

- `ThisWeekWell` now uses the shared `FolioGlassPanel` instead of the Reading desk's
  opaque `folioPaper` branch and the classic layout's older raised material. The
  weekly summary follows the main panel-opacity preference, shares the current
  data-panel diffusion and edge, and preserves chart content, spacing, entry motion,
  tap-to-Stats and navigation clearance.
- The revolving shelf now shows only its cover position (`1 / 3`, for example).
  The “Drag to turn” instruction is removed; gestures, buttons and their accessible
  labels remain available.
- `CoherentMaterialsTest` exercises the real weekly component in both Home layouts
  across light/dark, blur/minimal/off, and live low/default/full opacity, including
  restoring transparency after a full-opacity state.

Release/test/install evidence is recorded under `outputs/home-glass-*`. Phone
interaction remains install-only; no launch, navigation or frame-time claims.
