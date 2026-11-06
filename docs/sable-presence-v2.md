# Sable: everyday presence and the inhabited nook

This supersedes the placement and visual direction in [the original desk implementation](sable-reading-desk.md).

## Everyday presence

Sable shares the current-book composition. In Reading desk Home, Sable sits beside the revolving covers and notices a newly selected title. Classic Home keeps the companion inside its reading anchor, above the progress action. The separate companion card below the ledger is removed. Empty Home and empty book libraries introduce Sable alongside the first-book invitation. A new library arrival gets a small dismissible acknowledgement, cleared after 90 seconds; existing books on startup are not new arrivals.

Touching Sable pets them; holding stretches; stroking turns them toward your hand and finishes with a pet. Opening the nook is a separate labelled action. Reading, manga, and document readers keep their content clear; leaving a reader triggers a bookmark sequence on the next visible companion. Finished books trigger a celebration.

## Behavior and art

Eight full-body poses replace the six-cell joint atlas: seated, alert, two walking strides, investigating, grooming, stretching, curled sleep. A greeting notices the reader, approaches, and settles. Bookmarking investigates the page and returns to sitting or sleep. Quiet autonomous grooming, investigation and stretching are separated by 18–26 second pauses. Touches interrupt the current sequence. Each pose has a normalized foot baseline. The original v1 atlas is retained as source/reference.

Motion respects system animation permission, reduced motion, window visibility, viewport visibility, and the covered-Home policy. Animated progress is read in the character's draw pass. The room and atlas are cached per platform. No animation clocks or mascot overlays are added to reading pages.

## Nook

The full window-and-desk scenery appears only inside Sable’s nook, as explicitly chosen by the user. Home keeps its existing reading backdrop.

The square illustrated room preserves spatial relationships at phone and desktop widths: books and Shelf Shift on the left, Lantern Lines at the pendant, Book Balance at the stack, the journal at the leather notebook and saved passage at the paper note. Object targets sit above the character's transparent gutters. Day and evening variants keep the same layout and change the window and illumination. Late-night Sable curls up.

The current book uses its real cover when available. Finished titles add shelf spines. Completed scoring runs add keepsake lights; practice and abandoned runs keep their existing exclusion. The saved passage stays owned by the highlight repository. Existing games, run history, discoveries, purchases and storage keys are preserved. All five existing room decorations still affect the scene.

## Assets and provenance

Built-in image generation tool; no CLI/API fallback. Project assets:

- `shared/src/composeUi/resources/companion/sable-poses-v2.png` — transparent 4 × 2 full-body atlas, original generated dimensions preserved.
- `shared/src/composeUi/resources/companion/sable-nook-v2.png` — evening room.
- `shared/src/composeUi/resources/companion/sable-nook-day-v2.png` — matched daytime room.

### Pose prompt

Use case: identity-preserve. Asset type: production animation sprite atlas for Folio reading companion. Reference image is existing Sable atlas; preserve EXACT character identity: charcoal brown marten, cream throat, rounded ears, long low body and flowing tail, detailed soft natural fur, composed affectionate expression, painterly realism. Create ONE 2048x1024 transparent RGBA image in a STRICT 4 column by 2 row grid of eight equal 512x512 cells. Every cell contains one COMPLETE full body, side view facing RIGHT, same scale, feet baseline at y=465 within its cell. Tail left, nose right. Keep all body parts within 30px cell gutters. No background, floor, glow, shadow, props, words, borders or labels. Cell row1 col1: seated attentive head lifted. row1 col2: alert leaning forward with one front paw raised to investigate. row1 col3: walking right, front right paw forward, back right paw behind. row1 col4: next walking stride right, opposite paws forward. row2 col1: crouching, nose down sniffing bookmark on floor (no bookmark drawn). row2 col2: seated grooming, face bent down licking raised forepaw. row2 col3: long forward stretch with rump lifted, paws extended. row2 col4: curled sleeping with tail wrapped around body, nose tucked. CRITICAL consistent character and scale across cells, isolated on true transparent background. Beautiful fine fur edges, each pose anatomically credible; no detached heads or body layers.

### Room prompt

Use case: illustration-story. Asset type: square background illustration for an interactive reading-companion room in a sophisticated book reading app. Create a beautiful richly textured painterly realistic cozy reading nook, front-on view, 1024x1024. No animals, people, text, letters, logos, interface, labels. Dark walnut wooden desk fills bottom 28 percent of image, tabletop top edge at y=72 percent. Warm plaster wall, subtle teal botanical shadows. Upper LEFT quadrant a small wooden bookshelf with books, centered x22 percent y35 percent. Upper RIGHT quadrant an amber hanging lantern centered x84 percent y27 percent. Tall arched window centered x56 percent y30 percent, night blue twilight beyond, soft curtains. On desk LEFT edge at x15 percent y74 percent a short three-book stack. On desk RIGHT edge x86 percent y79 percent a closed small leather journal. Leave entire CENTRAL foreground x28 to78 percent y47 to85 percent clear empty tabletop space, where an animated marten will be composited later. On desk bottom center a small cream blank bookmark/note, no text. Carefully composed room perspective, physical materials and softly detailed textures matching fine natural fur illustration: realistic wood grain, fabric, paper edges, softly glowing glass, ambient occlusion, warm golden lantern light crossing cool blue window light. Elegant adult literary mood, restrained colors, intimate and atmospheric, no cartoon thick outlines. Edge-to-edge finished art, no border. Keep all important objects within 10 percent safe edges.

### Daylight prompt

Use case: lighting-weather. Edit target: the supplied Sable reading nook background. Create its DAYTIME lighting variant. Preserve EXACT geometry, framing, perspective, all object positions, wood grain, textures, books, lantern, journal, blank bookmark and composition. Change ONLY light and the view outside: softly sunlit late morning, pale clear blue sky and green wooded hills through the same arched window, sunlight entering diagonally onto the desk. Keep gentle warm cream plaster, muted natural colors, realistic painterly detail, a little golden warmth in the lantern (much dimmer than night). No added objects, text, people or animals. Keep square framing.

## Verification

35 focused desktop tests passed with no failures or errors. Coverage includes the action timeline, room object routing, pet/hold/stroke gestures, reduced and hidden motion, actual Home integration and visibility in both layouts, existing persistence, reading clock and all game engines. Render checks cover light/dark rooms and game routes at 320, 393 and 1000 pixels. Android release and desktop application compilation passed. The final compact-preview and narrow-heading adjustment was recompiled and passed the same desktop suite. The full room's labels clear Sable's face; compact game previews expose game targets without journal/passage controls.

Verification: `outputs/sable-presence-v2-verification.json`; full build log: `outputs/sable-presence-v2-final-check.log`; final layout check: `outputs/sable-presence-v2-layout-check.log`. Stable previews: `outputs/sable-presence-v2-home.png`, `outputs/sable-presence-v2-nook.png`, and `outputs/sable-presence-v2-night.png`.

On this machine the 2 GB Kotlin compiler heap thrashed during concurrent compilation. Verification completed with one worker and `-Pkotlin.daemon.jvmargs=-Xmx4g -XX:+UseG1GC`; no repository-wide memory settings were changed. The wrapper stopped Gradle daemons after each completed check.

## Installed release

Android 1.2.127 (181) was built as signed ARM64, ARMv7 and universal APKs on 2026-11-06. All three signatures and the three new embedded mascot assets were verified. The ARM64 APK was installed via ADB as an update on the connected CPH2717, retaining the existing app data directory. Launch succeeded and the app stayed running without a startup crash. APK: `outputs/Folio-1.2.127-arm64-v8a.apk`; verification: `outputs/sable-1.2.127-installed-verification.json`.

Release optimization required a 3 GB Gradle heap after the default 1.5 GB heap ran out of memory. The successful retry reused compiled files and changed no global memory settings.
