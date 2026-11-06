# Theme color distribution

The current twelve theme families are authored in `FolioArtDirection.kt`. Older
theme curation sheets describe retired palettes; this pass applies to the current
Classic, Paper, Forest, Ocean, Editorial, Blush, Acid, Porcelain, Linen, Risograph,
Celadon and Citrine pairs.

Each family gives progress its signature hue. The whole material combination now
carries its identity, including the persistent top bar and floating navigation:

| Pair | Light foundation → interaction | Dark foundation → interaction |
| --- | --- | --- |
| Classic / Charcoal | Chalk/steel → oxblood | Graphite/steel → amber |
| Paper / Ember | Vellum → indigo | Espresso → ice blue |
| Forest / Midnight | Moss → rust | Pine → apricot |
| Ocean / Abyss | Cyan glass → raspberry | Deep cyan → coral pink |
| Editorial / Crimson | Chalk → petrol | Oxblood → mint |
| Blush / Vaporwave | Rose → teal | Orchid → aqua |
| Acid Rave / Synthwave | Chartreuse → magenta | Lime-black → neon pink |
| Porcelain / Cobalt | Ice blue → ochre | Cobalt → saffron |
| Linen / Copper | Eucalyptus linen → copper | Verdigris → peach copper |
| Risograph / Afterprint | Peach newsprint → Prussian blue | Prussian blue → orange |
| Celadon / Amethyst | Jade → plum | Plum → mint |
| Citrine / Blacklight | Butter yellow → violet | Ultraviolet → citrine yellow |

Light and dark faces are independently authored. Reader stocks, categorical chart
palettes, and the picker descriptions follow each combination. Every shipped face
now uses a chromatic interaction accent that opposes its foundation and bar, rather
than repeating the same hue at another lightness. Afterprint and Blacklight retain
their reference combinations. Classic/Charcoal keep quiet neutral foundations with
oxblood/amber interaction. Custom achromatic palettes still remain neutral. Existing
IDs are stable.

The distribution separates element roles from identity cues:

- Selected navigation always uses the theme's interaction accent. The shared
  `navigationSelection` / `onNavigationSelection` pair supplies all destinations,
  including readable ink over the composited pill.
  `folioDestinationAccent` retains its old arguments for compatibility but no longer
  assigns different colors to different destinations.
- Each face explicitly authors `paperSurface`, `referenceSurface`, and
  `chromeSurface`. Paper, grouped reference data, and floating controls have
  different temperatures and consistent identities across destinations.
- `FolioTheme.elements` derives inset, feature, action, and selection roles from
  those planes. Read actions, chips, segmented controls, sliders, and input focus
  use the theme interaction role. `FolioSection` accents headings and small cues.
- In-flow panels diffuse the backdrop without optical lens/bevel treatment.
  Floating controls keep glass. Existing opacity and minimal/full glass settings
  still govern transmission and diffusion.
- Ink pairs are checked against the actual material roles. Custom palettes derive
  the new planes from the user's own colors instead of inheriting Classic's planes.
- Weekly reading and the activity heatmap use a stable progress hue; artwork
  cannot recolor the data series. Categorical charts retain separate series colors.

Manga History uses the same lazy grouped reference panels as manga sources and
extensions, with local calendar dates, shared gutters, typography, portrait covers,
and the bottom navigation inset. Cover/title transition identities and the clear
history confirmation are preserved.

The new Citrine palette uses `citrineglass`; the retired `citrine` alias continues
to resolve to Paper so existing saved preferences retain their previous meaning.

`ThemeDistributionRenderTest` writes full-window element compositions for all
24 faces with the actual `FolioTopBar` and `FolioNavigationCapsule`, all four
selected navigation states, and both blurred and fallback chrome. The capsule is
shared with the Android navigation host, including icons, geometry, feathered
selection, and material. Contact sheets preserve both bars instead of cropping
the content alone. The test also writes
History renders at 320 dp with large type, 393 dp, and 900 dp. It also covers empty
history and full/minimal/fallback glass. `ThemeSchemeTest`, the material contrast
check, `ElementColorRolesTest`, and `DesignSystemTest` validate material/control
ink pairings, uniform navigation selection, theme flipping, legacy alias stability,
identity independence, palette integrity, and progress-track contrast. A whole-set
comparison checks same-polarity faces for separation across paper, reference,
chrome, background, and signature; material separation is also checked without the
signature, so changing only a button color cannot pass. `ThemeChromeTest` checks
the shipping capsule's selection behavior, labels, and four 48 dp touch targets.
`ElementColorRolesTest` additionally checks selected-pill hue against the actual
bar composite at both chrome opacities and field endpoints. Supporting semantic
accents must occupy distinct hue families rather than differing only in shade.
