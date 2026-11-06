# Folio color distribution: research and proposal

Date: 2026-11-02. Status: implemented in the shared theme/material/control layer.

2026-11-03: navigation selection now uses one theme-wide accent on every destination.
The new Risograph/Afterprint, Celadon/Amethyst, and Citrine/Blacklight sets extend
the registry to twelve pairs, each with app, reader, chart, and material roles.

Implementation uses authored `paperSurface`, `referenceSurface`, and `chromeSurface`
on each built-in face, with derived `FolioTheme.elements` roles for actions,
selection, inset controls, feature surfaces, and paired inks. Custom themes derive
these planes from their own colors. Section accents remain on identity cues;
functional controls and reference surfaces no longer inherit them. Foundation
chroma is restrained, in-flow panels use diffuse reference material, and floating
controls retain optical glass and the existing opacity preferences.

`ElementColorRolesTest` checks ink pairings and that section/artwork changes cannot
recolor settled functional controls. `ThemeDistributionRenderTest` renders the same
element composition in all 24 faces, all four navigation selections, new pack
picker previews, and populated/empty History layouts. Existing
material, interaction, manga hub, settings/reader, and statistics render checks
cover the surrounding surfaces and opacity behavior. The directions below remain
the design rationale; final colors are authored in `FolioArtDirection.kt`.

## Recommended direction

Develop an editorial interface with distinct paper, reference, and floating-control
materials. Let the theme set their temperature and hue relationships. Let function
set their emphasis. Artwork supplies much of the library's strongest color.

The earlier destination-accent pass does not fully solve element-level distribution.
A section accent should decorate a section without becoming the default color for
every action, panel, border, and icon beneath it.

## Research findings

- Material 3 separates accent roles from surfaces and provides multiple surface
  container levels. This supports separating foreground emphasis from the large
  areas that establish hierarchy. Its guidance also keeps layout-region color
  mappings consistent across responsive breakpoints. [Material color roles](https://m3.material.io/styles/color/the-color-system)
- Adobe Spectrum uses neutral ramps for background layers, component states, and
  borders. Its approach demonstrates that structure does not need a new saturated
  hue for every tier. [Spectrum grays](https://spectrum.adobe.com/foundations/color/grays)
- Atlassian distinguishes freely interchangeable accents from colors with state
  meanings such as success and warning. It also provides different accent tokens
  for backgrounds, text, icons, and borders instead of applying one swatch to all.
  [Atlassian accents](https://atlassian.design/foundations/color/accents/)
- Apple's material guidance places Liquid Glass in a functional layer for controls
  and navigation, and advises restrained use. This is an Apple platform guideline;
  the transferable principle for Folio is separation between content and floating
  controls. [Apple materials](https://developer.apple.com/design/human-interface-guidelines/materials)
- Carbon distinguishes categorical, sequential, and alert palettes. Data should
  determine the encoding: different categories need separable hues, while a
  magnitude can use a tonal sequence. [Carbon palettes](https://www.carbondesignsystem.com/building-blocks/data-visualization/color-palettes)

These are design-system guidelines, not evidence that a particular Folio palette
has been usability-tested. The allocations below are recommendations to prototype.

## Element-level allocation

| Category | Proposed role and treatment |
| --- | --- |
| Page background | Low-chroma foundation with a restrained theme tint; atmospheric gradients strongest in empty margins. |
| Reading content, quotes, notes | Paper family with its own subtle temperature; near-neutral text and stable, quiet fill. |
| Settings, history, download and source groups | Reference family: another low-chroma temperature, consistent across screens, grouped rather than many individual lenses. |
| Inputs and embedded controls | Inset tone within their parent family; avoid unrelated saturated fills. |
| Featured content | One stronger thematic container when needed; ordinary peers retain quiet surfaces. |
| Navigation and floating controls | Distinct material and elevation; restrained default tint, clearer selected controls. |
| Main actions | Theme interaction accent with the strongest routine emphasis. Read/Continue retains its role across screens. |
| Secondary actions | Tonal fill or colored text; routine icons can stay neutral. |
| Selection | Soft interaction-accent container with a clear indicator, check, underline, or label treatment. |
| Text | Primary and secondary ink tokens paired to their actual materials; body copy and timestamps remain readable. |
| Borders and rules | Surface-relative neutrals; stronger where needed to identify an input or state. |
| Progress | One stable progress role throughout the app; a subdued track with sufficient separation. |
| Multiple data series | Chart-specific categorical palette, with persistent identities and labels. |
| Status | Dedicated error, warning, success, and informational roles, accompanied by text or icons. |
| Cover-derived color | Local halo, featured backdrop, or small detail accent; bounded so artwork cannot redefine global controls or status meanings. |

The hierarchy is deliberately uneven: foundations occupy the largest areas;
material variation is repeated but quiet; strong accent fills are sparse. No
fixed 60/30/10 or 70/20/10 proportion is a universal accessibility or usability rule.

## Separate identity, emphasis, and state

Each theme needs an authored foundation, paper tone, reference tone, interaction
accent, and counteraccent. Each chromatic role needs foreground, subtle-container,
and bold-container variants as appropriate. A tint intended for a large panel is
not automatically suitable for small text or an icon.

Keep the interaction role consistent within a theme. A Read button should not
change hue merely because it appears in Library instead of Home. Section identity
can appear in a heading, selected tab, icon tile, or feature accent without dyeing
every child component. Counteraccents are for occasional emphasis and secondary
meaning, not every adjacent peer card.

Status roles remain recognizable between themes. A completed book is usually
ordinary metadata, not an urgent success message; neutral text and a check can
be enough. A failed download deserves an error signal. Red family accents need
separation from errors through tone, context, icons, and labeling.

## Proposed family directions

These are color relationships for exploration, not final hex values.

| Family pair | Foundation / content | Interaction | Supporting counter-tone |
| --- | --- | --- | --- |
| Classic / Charcoal | Neutral white, gray, and charcoal | Graphite / pale neutral | Tonal variation; artwork supplies chroma |
| Paper / Ember | Parchment paper with subdued smoke/slate reference surfaces | Bronze / copper | Dusty blue or muted plum |
| Forest / Midnight | Ivory paper and gray-green reference surfaces | Moss / soft jade | Amber |
| Ocean / Abyss | Pearl paper and blue-gray reference surfaces | Ocean blue | Coral with restrained sea-glass support |
| Editorial / Crimson | Cream paper and stone/verdigris reference surfaces | Garnet / rose | Muted teal |
| Blush / Vaporwave | Pearl paper and mauve-gray reference surfaces | Rose | Aqua |
| Acid Rave / Synthwave | Chalk / graphite with very restrained green tint | Olive / lime | Electric blue in small areas |
| Porcelain / Cobalt | Porcelain paper and cool slate reference surfaces | Cobalt | Apricot or restrained copper |
| Linen / Copper | Linen paper and stone reference surfaces | Copper | Sage or muted blue |

Light and dark versions preserve relationships and relative emphasis, rather than
inverting RGB values. Tune their tones, chroma, and foreground pairs independently.
In a rose theme, for example, a dark container can remain plum-gray while its
small active mark becomes luminous rose; the entire card need not glow pink.

## Behavior by screen

- Home: a featured reading item can use artwork and a more expressive surface.
  Secondary content and charts should retain clear differences in emphasis.
- Library: covers dominate the chromatic composition. Keep the frame and metadata
  quieter; use one clear selection treatment across book, document, and manga items.
- History and downloads: use grouped reference surfaces. Dates are structural
  labels. Resume/Read gets the interaction role; errors and ongoing downloads get
  their appropriate state/progress roles.
- Statistics: keep containers quiet so chart marks can carry color. Seven bars
  showing the same measure across seven days use one hue, unless another meaningful
  category is being encoded. Genres or formats may use distinct category hues.
- Quotes and notes: let paper and typography establish identity. An annotation
  mark can use a supporting tone. User-chosen highlight identity should remain stable.
- Settings: neutral reference groups and predictable control states. Avoid
  saturating every category icon, row, boundary, and switch at once.
- Reader: reading content governs the composition; floating controls use a bounded,
  legible material, with a quieter treatment than a promotional home-page feature.

## Transparency, states, and contrast

Use glass where foreground controls actually float over content. For data and
text-heavy lists, prefer stable, matte or softly diffuse surfaces with minimal
optical detail. A History panel and a settings panel should belong to the same
material family, while a floating navigation capsule may legitimately differ.

Selected controls need more than a hue change. Hover, pressed, focus, and disabled
states should form a coherent progression; keyboard focus must remain visible
alongside selection. Do not treat reduced opacity as a complete state system.

Contrast must be checked against the final composited background, including
glass, gradients, artwork, and opacity preferences. Preserving a numerical color
coordinate or using the same alpha across themes does not establish readability.
Material Color Utilities' HCT model explicitly separates hue, chroma, and tone,
which is useful when constructing tonal variants. Final adjacent color pairs
still need validation. [Google HCT implementation](https://github.com/material-foundation/material-color-utilities/blob/main/typescript/hct/hct.ts)

Use these baseline checks:

- Normal text, including informative metadata: at least 4.5:1.
- Qualifying large text: at least 3:1.
- Visual details necessary to identify controls, states, or meaningful graphics:
  at least 3:1 against adjacent colors. This does not require every decorative
  separator or card boundary to meet 3:1.
- State and category meanings must have a visible cue beyond hue, such as text,
  a check, an icon, a pattern, or an appropriate shape distinction.

Sources: [WCAG text contrast](https://www.w3.org/WAI/WCAG22/Understanding/contrast-minimum),
[non-text contrast](https://www.w3.org/WAI/WCAG22/Understanding/non-text-contrast.html),
and [use of color](https://www.w3.org/WAI/WCAG22/Understanding/use-of-color.html).

## Implementation and review order

1. Prototype the same representative composition in Classic, Forest, and
   Blush/Vaporwave. Include cover art, paper, data panels, chrome, one main action,
   a selected item, chart marks, and an error. Keep content and layout identical.
2. Make element roles explicit at the shared material seams. Foundation, paper,
   reference, inset, chrome, action, selection, feature, ink, status, and chart roles
   should not all inherit one route accent. Reuse existing tokens when they already
   express the intended job rather than creating duplicate registries.
3. Apply the role hierarchy to ordinary components and states before tuning
   decorative gradients or borrowing more colors from artwork.
4. Review Home, Library, History, Statistics, Settings, and Reader in all family
   pairs with full, minimal, and disabled optical effects and multiple fill settings.
5. Check grayscale hierarchy, color-vision simulations, large text, pointer/keyboard
   states, and contrast on actual rendered combinations. Follow with user review;
   passing a swatch test alone does not establish a successful composition.

Success means users see content first, recognize the main action, distinguish
paper from reference controls, and retain predictable interactions across themes.
