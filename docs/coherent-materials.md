# Coherent panels and transparency

Release: 1.2.105 (159).

## One material path

More groups and Android settings sections use `FolioGlassPanel`, the same diffuse glass renderer as statistics. Reference panels and their modifier also route through this renderer, covering shared desktop settings, book/manga reference content and download rows. The More icon tiles and embedded settings choices use thin edges and quiet washes, without the older layered bevels or additional nested backdrop captures.

The existing `panelOpacity` preference controls these surfaces, Library hero fills and optical reading actions. No new preference or migration is needed. The designed defaults are preserved: data panels use their existing diffuse tier, reading actions keep their 0.38 optical tint and Library jackets retain their 0.82 tint. Values below the designed point thin proportionally; values above it now interpolate to 1.0. Reading actions no longer clamp the entire slider into 0.18–0.65, and their painted fallback follows the preference as well. At 100%, the renderer skips backdrop capture and paints a fully solid plane.

The glass-effects and minimal-glass switches continue to choose rendering quality and diffusion. They do not disable the opacity preference. Printed paper and reading pages keep their deliberately matte material; app bars and in-reader controls retain their separately labelled opacity settings.

## Preview

The transparency miniature uses the real diffuse glass modifier and an isolated source registry containing its own theme field. It no longer quotes the old bevelled panel or samples the settings page around it. The reading-room preview includes a real glass panel and reading action and explicitly reads the previewed settings' opacity.

## Validation

`CoherentMaterialsTest` checks live opacity changes and restoration across light/dark themes, optical rendering, minimal mode and no-blur fallback. It tests the actual shared settings panel, reference panel, inline reference modifier, Library hero and reading control. At full opacity, changing a red backdrop to blue must leave sampled pixels unchanged. The transparency miniature is checked for independence from its parent page.

The broader checks include reading-room native renders, existing dock optics, statistics translucency, capability fallbacks, opacity tiers and settings snapshot protections. Result records are saved in `outputs/coherent-materials-*-verification.json`.

Device interaction is install only, per the user's preference.

## Reader, sheen and vividness follow-through

Reader bars and rails retain the separate in-reader opacity preference. Reader-owned action/font menus and the note editor now follow the reader panel opacity instead of a pinned popup fill. Open menu/dialog fill values are snapshot state and update by token, preserving their identity, focus and nested popup ownership. The embedded-page reader overlay also receives reader panel opacity and metallic sheen; at lower opacity it requests CSS backdrop diffusion, and at 100% it paints a solid fill without blur. Turning sheen off removes its highlight layer.

The existing root vibrancy pipeline continues to drive room fields, pools and palette-derived materials. Cached artwork projected into glass now responds too, through a remembered saturation filter on the projection only. The displayed cover bitmap and reading page are unaffected. The shipped projection is preserved at the default vibrancy position, and Home's reduced 0.52 projection strength is retained. Metallic sheen scales the new optical reading control's highlights while leaving its opacity and refraction separate.

Additional checks cover the actual reader island, embedded reader preference updates, nested popup ownership during material updates, and a cached cover whose projected light changes with vividness while its thumbnail pixels remain unchanged.

The user is taking the phone out: prepare the APK only; do not install or interact with the phone.

The legacy veil's gradient no longer reduces its footer alpha at a solid preference. Fully opaque legacy reader surfaces also skip their backdrop/lens pass, matching the new material's solid endpoint. The native reader regression compares red and blue backdrops at 100% with blur both enabled and disabled.

The opacity illustration uses a canonical miniature text scale and an icon reading-action sample; the surrounding settings labels and controls retain system text scaling.

Final verification: release build succeeded, all 58 tests across 13 suites passed, and all three APK signatures matched the existing app certificate. ARM64, ARMv7 and universal APKs are in `releases/`. No device installation was performed.
