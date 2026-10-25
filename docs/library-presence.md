# Library presence and quieter Home artwork

Release: 1.2.104 (158).

## Visual changes

The featured book and manga entries use a shared reading-jacket surface: gentle cubic shoulders, a faint directional edge and a local cover-colour light that falls off across the panel. The fill is translucent and palette-relative; there are no animated rims or new cast shadows. Covers grow to 124dp at standard text size. Enlarged text uses an 80dp cover to keep the title readable; progress details are stacked to keep percentages and chapter counts readable. The whole jacket is clickable, including its padding. Existing shared-cover transitions, selection, long-press menus and navigation callbacks are preserved.

Home's revolving desk keeps the existing artwork source, cover rotation, optical action dock and motion settings. Its projected artwork strength is now 0.52, down from 1.0. This reduces the colour/image contribution to the field and the glass sampling it, without changing the global transparency preference or the materials on other screens. Library and detail artwork keep their existing projection strengths.

## Verification

Native Compose renders cover light/dark themes at 393px and 320px with 140% text. All 20 focused tests passed, including desk rotation, reading-dock glass and reading-room behavior tests alongside the renders. Final test, release signature and install records are in `outputs/library-presence-*-verification.json` and `outputs/library-presence-device-install.json`.

ADB installation replaces the signed app in place. Device interaction is install only, per the user's preference; no launch or gesture testing is claimed.

Release build succeeded. All three APK signatures verified against the existing app certificate. ADB installation succeeded; the connected phone reports versionName=1.2.104 and versionCode=158. The app was not launched.
