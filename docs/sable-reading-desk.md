# Sable and the reading desk

The placement, behavior and art described here document v1. See [Sable presence v2](sable-presence-v2.md) for the current Home integration and inhabited nook.

Accepted direction: all six integration changes, with Sable chosen from the concept sheet before implementation.

## Character
Sable is a charcoal marten with a cream throat, rounded ears, a long back and a flowing tail. The expression is composed; warmth comes from attention rather than cartoon faces. A detailed transparent fur atlas, generated from the selected concept, supplies separate torso, tail and open/closed-eye head layers plus resting/stretching poses. Native transforms articulate the joints without flattening the illustration. Directional daylight, theme polarity and the current cover lend small lighting changes while the fur stays charcoal. Breathing, blinking, ear turns, head tracking, leaning into a stroke, stretching and settling beside a book replace the pangolin roll. System animation preferences and reduced motion apply.

## Everyday placement
Home retains a compact companion card in the earlier card position after the reading ledger. Its layout and reading content stay intact. The full desk scene is inside the companion destination. More contains a normal Reading desk row, also searchable by Sable, companion, games and leaderboard. The library and reader bars have no companion buttons. Reading stays clear of mascot overlays and reward popups.

The desk contains the current bookmark, a finished-book shelf, the latest live saved passage and a short return-session line. Sable's posture and voice react to night reading, highlighting, returning to a familiar book, exploring different authors and the choices in shared discoveries. No trait counters appear in the room.

## One room, three games
Tap the shelf for Shelf Shift, hanging lantern for Lantern Lines or book stack for Book Balance. An object opens a focused introduction with Play, today's board, practice and the personal leaderboard. The game rules and saved scores are preserved. Every finished scoring run changes the corresponding room object; higher lantern scores and taller balanced stacks add small details. A new personal best also enters the journal with the book being read at the time. Practice and abandoned runs do not earn keepsakes or enter ranked scores.

Games remain unlimited. Reading earns room details and new shared discoveries; it never changes game score multipliers or attempts. Old saved attempts and companion progress retain their existing local storage keys.

## Reading cycle
Read → return to the desk → see the kept bookmark / session line → optionally share a discovery or play → see the room change → return to the book. Discovery choices stay voluntary. The welcome discovery is free; subsequent discoveries follow each 15 accumulated minutes of reading or listening. Highlight text remains in the highlight repository: deleting a passage removes it from the desk rather than leaving a copied quote behind.

## Navigation and clutter
The main desk shows the scene, one contextual action and a journal entry. There is no arcade/Pet tab strip or Pet/Tumble/Offer Book button row. The journal holds memories, completed titles and attempts, with room setup behind a disclosure. Gameplay retains its themed board, pause/save, accessibility controls and full attempt history.

## Verification
Run the existing engine, persistence, touch-gesture and reading-clock tests, plus room/object navigation and keepsake tests. Render the desk, game objects, journal and games in light/dark at narrow Android and wider desktop sizes. Compile Android release and desktop. Build signed Android release in a fresh process, verify signatures, then install over the existing app without clearing user data when ADB is available.

## Character art provenance
Built-in image-generation tool; reference: the selected Sable concept. Asset: `shared/src/composeUi/resources/companion/sable-atlas-v1.png` (1536 × 1024, RGBA). Six 512px cells: torso, open head, tail, closed head, resting, stretching. Prompt: detailed charcoal marten matching the left concept, natural proportions, cream throat, fine fur, restrained expression; transparent layered 3 × 2 animation atlas; identical head alignment, no labels or background. The source asset is preserved unchanged; crop rectangles are applied by the renderer. Both platforms load the same image once.

## Verified release
Android 1.2.126 (180): signed ARM64, ARMv7 and universal APKs built successfully. All three signatures and their embedded Sable atlas bytes verified. Thirty focused desktop tests passed; Android release and desktop compilation passed. Visual review covered 72 current layouts and 76 animation frames. The 320px Home card, full desk and corrected night pose were inspected. Installation is pending: ADB and Windows USB enumeration showed no phone at completion.
