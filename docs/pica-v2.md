# Pica: companion and pocket arcade, second design

The first version failed in three ways: the pet was a static icon, the ricochet game offered little meaningful variation, and its bespoke dark canvas looked disconnected from Folio. This revision replaces that primary experience rather than adding another coat of styling.

## Research and decisions

- [Balatro / Playstack](https://www.playstack.com/games/balatro/): changing combinations and synergies make repeated runs interesting. Use visible per-run objectives and seeded variations, not ten rearrangements of one shot.
- [Triple Town / Spry Fox](https://spryfox.com/our-games/triple-town/): limited space makes each placement consequential. Shelf Shift is a spatial merge puzzle with random incoming pieces, an upcoming-piece preview, and increasing targets.
- [Two Dots / official help](https://dots.helpshift.com/hc/en/3-two-dots/faq/382-why-does-the-game-prompt-me-to-make-squares/): a single gesture can have a larger payoff. Lantern Lines connects matching symbols; long chains and closed loops have different rewards.
- [Stack / Ketchapp](https://www.ketchappgames.com/game-stack): immediate one-touch feedback supports fast retries. Book Balance is a separate timing game: align moving books, lose overhang, build a perfect streak, and recover width through precision.
- [Finch / discovery conversations](https://help.finchcare.com/hc/en-us/articles/37779979512845-Going-on-an-Adventure): attachment comes from shared experiences and remembered responses. Reading unlocks short discoveries; choices change Pica's persistent temperament and memories.

These are design references, not claims that a new game has proven retention. Use original artwork, interface, names, scoring, and progression. Validate controls and pacing on the connected Android phone.

## Scope

1. Three genuinely different games: swipe/merge, connect/clear/refill, and tap/timing. Shelf Shift also drafts scoring bookmarks every 12 moves (up to three), so corner play, small merges, consecutive merges, and large merges support different run strategies. Independent personal leaderboards, seeded daily challenges per game, recent attempts, save/resume, practice, and instant retry. Retain the original ricochet history as an archive.
2. A vector animation rig: breathing, blinks, head turns, articulated tail and feet, reading, dozing, pet response, curl/roll, hop, and celebration. Real tap, hold, and stroke interactions; honour reduced motion.
3. One application-level companion state. Pica appears in normal mastheads and visible reader controls, reacts when reading resumes/ends and rewards arrive, and opens the same companion space from any screen. Quiet during actual reading. Home is an introduction, not the sole entry point.
4. Folio's own backdrop, glass/paper surfaces, action controls, rules, typography, accents, and spacing throughout. No unrelated dark game canvas or stock Material card hub.
5. Persist affection, curiosity, calm, playfulness, discoveries, memories, decorations, and all game attempts. Reading affects cosmetics and the relationship, never competitive scoring.

## Validation

Test merge ordering/no-op moves, generated board playability, loop detection and refill, timing/overlap/perfect recovery, seeded replay, per-game ranking, checkpoint finalisation, old-save decoding, and trait/discovery persistence. Render actual new screens across themes and small phones. Exercise the controls on Android, then build and install the signed release without clearing data.
