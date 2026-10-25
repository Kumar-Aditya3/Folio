# Folio / Quiet signal

Initial art-direction exploration, 2026-10-21. The broader implemented pass and
phone-review checklist are documented in [quiet-signal-implementation.md](quiet-signal-implementation.md).
The compositions below record the original study. The user's latest brief —
**quiet, modern, minimalist cyberpunk** — supersedes the earlier atelier suggestion.

## Identity

A private reading instrument: calm at rest, precise and subtly luminous in use.
Keep real artwork expressive and the surrounding interface restrained. Familiar
book typography can coexist with a modern technical interface; the reader's chosen
page font remains theirs. The signature should survive with all effects disabled.

The study in `../outputs/folio-quiet-signal-study.png` uses sample content and authored
placeholder covers. It is a static composition study, not a screenshot or proof of
optical rendering. It deliberately preserves the current three-cover Home structure.

## A recognizable visual grammar

**Paired index strokes** are the proposed signature: one short bright line and one
quieter offset line, recalling a bookmark and its page edge. Use the pair for the
current reading action, an active collection, and a saved passage's source. Give it
meaning rather than repeating it on every row. It must supplement a label/checkmark,
never replace one. A subtle paired-stroke brand mark can be explored separately;
this study does not replace the app icon or logo.

**Three material roles:** graphite content fields, smoked optical controls, and
neutral matte passages. Existing translucent controls retain their actual captured
content, with enough veil to read. A tiny cool edge catch defines active controls;
a slight secondary hue appears only at a short specular edge during touch. Covers
keep their actual colors and near-square geometry. Passage cards stay matte and
legible. The design needs local contrast and hierarchy, not a uniform color wash.

**Shape discipline:** content stays open or on softly shouldered sheets. Broad
controls retain the existing 20–26dp family; small choices use nested inset curves.
Corners remain aligned with padding. The index detail supplies the distinctiveness,
rather than cut-off corners, ornamental notches, or harsh sci-fi polygons. Sparse
0.5–1dp rules carry hierarchy without drawing a glowing box around everything.

**Type:** clean proportional sans for navigation and controls, editorial book titles,
and small monospace numerals only where alignment helps: time, progress, chapter
position. All essential labels remain ordinary readable text. No all-caps terminal
body copy or tiny technical labels. No arbitrary catalogue numbers, fictional system
statuses, scanlines, glitching, or continuous background particles.

**Color:** start with graphite and silver-white, with a desaturated cyan as the
study accent. This is a direction sample, not a replacement for the user's theme
system. Implement through palette-relative roles, preserving existing theme choices.
Light themes should express the same identity through cool off-white, graphite ink,
and darker signal strokes. Reserve a second hue for a restrained interaction edge;
error, warning and success colors retain their meanings. Do not recolor cover art.

## Interaction gives the identity its life

1. **Home → book → reader:** the selected cover remains the same object. The small
   progress signal and reading action carry through the transition, then recede as
   the actual page appears. Keep the approved revolving cover gesture and subdued
   artwork contribution. Title and action state follow selection without new loading.
2. **Library:** stronger typographic alignment, open cover rows, a short progress
   signal below each in-progress title, and a clean selected state. Filters expand
   from their existing anchor. Search must stay lightweight after the recent fix.
3. **Reading controls:** a local light response follows a slider thumb or pressed
   control, then settles. The settings drawer remains a substantial readable plane;
   menus stay inside it. The opacity setting continues to govern real material fill.
4. **Saved passages:** matte text sheets, source title/cover and paired index marker.
   Expand from the selected passage; keep actions and its source in the same place.
5. **Stats:** numbers, precise thin chart marks and a small date selection signal.
   No large floating navigator or extra chart chrome. Real information supplies density.
6. **More/settings:** the same typography, nested shapes and active-choice marks.
   Shared surfaces and current transparency plumbing remain the source of truth.

Proposed starting timings: 120–180ms local feedback; 220–300ms anchored disclosure;
existing shared-cover motion for navigation. These are tuning candidates, not measured
performance improvements. Reduced motion substitutes stable selection plus a brief
fade; no moving scan or glowing idle animation. Feedback does not block navigation.

## Implementation map from the code audit

| Existing seam | Proposed work |
| --- | --- |
| `ui/theme/FolioAtmosphere.kt` | Theme-relative quiet field and restrained local light; retain palette luminance protections. |
| `ui/components/FolioMaterials.kt` | Keep paper/reference/control distinctions; consolidate nested shapes and add index decoration outside text bounds. |
| `ui/components/Glass.kt` | Keep sole Haze integration and room/content source ordering. Add only bounded local edge response; preserve opacity and capability fallbacks. |
| `ui/home/ReadingDeskHero.kt` / `FolioActionDock.kt` | First visible slice: paired index, calmer metadata hierarchy, coordinated progress/action continuity. Preserve carousel and full lower list. |
| `ui/components/FolioSharedElements.kt` | Reuse book/manga/document identity keys; avoid new per-frame data requests or layout measurement. |
| `ui/components/FolioChromeMaterial.kt` | Consistent subtle active navigation signal; preserve existing contrast and knob mapping. |
| `ui/reader/ReaderTypefacePicker.kt` | Names now preview real generic/imported faces; keep the new in-drawer selection behavior. |

No extra visual engine is needed. Cached Compose drawing, existing motion tokens,
shared-element infrastructure, Haze and current cover caches support this proposal.
Any future shader enhancement should have a specific visible purpose and fallback.

## Recommended next experiment

Implement **Home's reading action, Library's active reading entries, and the reader
control plane** as one opt-in identity experiment. This is enough to evaluate a full
browse-to-read loop. Keep app-wide migration for after that experiment is judged in
use; the user likes the current app and asked to explore the direction first.

Review in dark and light themes, 320dp and normal width, large text, reduced motion,
no-blur fallback, and minimum/default/full material opacity. Confirm the selected
book and saved passage remain recognizable in grayscale. Frame timing must be
measured before making smoothness claims. Native font-picker tests and a release
build validate the font fix separately; static concept art does not validate UI.

## Research references and interpretation

- [Apple: Meet Liquid Glass](https://developer.apple.com/videos/play/wwdc2025/219/):
  functional controls form a distinct layer; local interaction changes the material.
  Folio can use that hierarchy without copying Apple's shapes or platform behavior.
- [Material: Shape as expression](https://m2.material.io/design/shape/shape-as-expression.html):
  shape, color and typography can work together as a recognizable visual language.
- [Material 3 motion](https://m3.material.io/styles/motion/overview/how-it-works):
  use coordinated expressive motion at meaningful interactions, retaining reduction.
- [teenage engineering: OP-1 overview](https://teenage.engineering/products/op-1/original/overview):
  a technical instrument can make controls approachable through clear visual mapping.
  The inference for Folio is useful signals, rather than decorative technical clutter.

These sources support individual design principles. The quiet cyberpunk treatment,
paired index motif and proposed screen compositions are Folio-specific proposals.
