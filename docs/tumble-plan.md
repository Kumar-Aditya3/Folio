# Tumble / Pica implementation plan

## Product

An optional, offline arcade game in Folio, starring Pica, a pangolin with folded-paper scales. A run lasts at most 120 active seconds and starts with three hearts. Drag to aim, release to launch, collect at least one lantern, and land in the nest. Clear successive shelf chambers. Three lanterns make a perfect shelf and increase the next shelf's multiplier, up to ×5. A miss loses a heart, discards that chamber's points, and resets the multiplier. Safe clears preserve it. Retry immediately after a result.

## Delivery sequence

1. Shared deterministic game engine: fixed simulation steps, predictable collision response, seeded chamber templates, three hearts, scoring, timer, and run records. Test reachability, scoring, misses, timing, and restore.
2. Durable local history and active-run checkpointing, separate from reading statistics. All completed runs, recent/score/date views, result placement, run detail, seed practice, and a fixed-seed daily challenge. Practice never enters ranked boards. Version rankings when rules change.
3. Shared Compose interface for Android and Windows: paper-library playfield, drawn pangolin, aim guide, keyboard aiming alternative, pause cover, app-background pause, accessible controls, compact and wide layouts, immediate restart.
4. Home entry and Pica's nook: optional presence, decorations earned from accumulated reading time, no score-affecting upgrades. Avoid reader overlays and interruptions. Keep earned progress when reading totals shrink.
5. Compile both platforms; run focused engine/persistence tests and offscreen render checks. Inspect the resulting images, fix issues, and document any remaining limitations.

## Rules v1

- 100 per lantern + 150 per safe landing + 250 for all three, multiplied by the multiplier at launch.
- Perfect landing increases the next multiplier by one, maximum five; a miss resets to one.
- The clock runs while aiming and in flight; explicit/background pause covers the board and stops simulation.
- Points bank only on a successful landing. A chamber resets after a miss, preventing farming.
- Rank by score, perfect shelves, then cleared shelves. Equal results share a rank.
- Ranked arcade and each daily challenge have separate boards; practice and abandoned runs stay in history only.
- Persist seeds, rule version, shot inputs, shelf outcomes, and the active simulation state. Completed run IDs are idempotent.

## Integration / boundaries

Use the existing reading environment's settings storage for local game data, with an independent repository and keys. Do not modify book progress or the existing reading clock. Add an optional manga time input to Home if available. Reading grants decorations only. Existing reading history establishes initial nook progress; totals use a persisted high-water mark to survive deletions. No network service or account is required. Sync and public leaderboards are outside this version.

## Acceptance

- A new user can discover and play without importing a book.
- Multiple runs survive repository reload; filters and ranking use the correct mode/rules/date.
- A paused or backgrounded game consumes neither time nor hearts.
- Every authored template has a safe route and a discoverable perfect route.
- Restore continues the same board and timer; finalisation cannot duplicate a run.
- Home can hide the companion and restore it; decorations never affect ranked play.
- Both platform source sets compile; focused tests pass and renders are inspected.

## Later playtesting

Measure voluntary retries, whether players understand misses, score improvement across attempts, safe/greedy route balance, and time waiting for a shot to resolve. Tune board timing and physics before calling the game addictive. Expanded animation/audio, replay playback, sharing/export, cloud sync, and new obstacle families follow that evidence.

## Implemented

- Shared fixed-step engine, ten templates with verified safe/perfect routes, immediate retry, arcade/daily/practice modes.
- Durable local attempt history, ranked and recent views, date/challenge filters, detail, checkpoint restore, and duplicate-safe finalisation.
- Compact Home entry below the reading summary and on empty libraries; hidden-card fallback link.
- Pica's drawn poses and reactive personality: late hours, returning reader, reading goal, finished books, personal bests, missed runs, and successful trick-shot history.
- Nook decorations from measured book/listening sessions and manga statistics; local high-water mark, no power upgrades.
- Android lifecycle/screen-off pause, opaque pause cover, platform haptics, reduced decorative motion, drag aiming and labelled alternative controls.

The first validation pass passed all 12 focused tests and rendered 36 combinations of phone/desktop sizes, light/dark themes, and Home/game/controls/history/result/nook surfaces. A narrow-phone Home-card wrapping issue was corrected after inspection. No Android device or emulator is configured on this machine, so hands-on touch and game-balance testing remain a device-playtest step.

Final validation: Android debug APK assembly succeeded; the Windows app and shared code compiled successfully after the last Back-button change. All 12 focused tests passed again, and the corrected narrow-phone render was inspected. On this memory-constrained machine the successful build used one worker, in-process Kotlin compilation, a 1536 MB heap, Serial GC, and two active processors. No persistent build settings were changed.
