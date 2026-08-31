# Moving this Qoder chat + project to another PC

Written 2026-08-31 from the Folio session on the old machine, before a PC swap.
This file lives in the repo, so it travels with `D:\projects\Folio` itself.

## 1. What actually holds the chat

Two separate stores. Copying only one gives you half a conversation.

| Store | Path on old PC | Contains |
|---|---|---|
| Transcript bodies | `%USERPROFILE%\.qoder\projects\D--projects-Folio\` | One `.jsonl` per chat + a same-named sidecar folder, and the project `memory\` folder |
| Session index | `%APPDATA%\com.qoder.app.stable\main.sqlite` (+ `main.sqlite-wal`) | The list the app's UI reads to show chats |

The chat in progress is `698eecde-dddf-44c9-acac-d0101a321aa4.jsonl` (~14 MB) with its
sidecar folder `698eecde-dddf-44c9-acac-d0101a321aa4\`.

Verified on this machine, not assumed: `main.sqlite` contains that session id 475 times
alongside `sessions` / `messages` / `chat_sessions` strings, and `main.sqlite-wal` contains
it 847 more times. **The WAL holds the newest turns and is not yet checkpointed into the
main DB** — copy both files or the tail of the conversation is lost.

## 2. Copy list

Roughly 215 MB total.

```
%USERPROFILE%\.qoder\projects\D--projects-Folio\      (whole folder, ~139 MB)
%USERPROFILE%\.qoder\memory\                          (user-scope memory, small)
%APPDATA%\com.qoder.app.stable\main.sqlite
%APPDATA%\com.qoder.app.stable\main.sqlite-wal
%APPDATA%\com.qoder.app.stable\main.sqlite-shm        (if present)
```

Optional, saves reconfiguring: `%USERPROFILE%\.qoder\settings.json`,
`%USERPROFILE%\.qoder\mcp-router.json`, `%USERPROFILE%\.qoder\plugins\`.

**Do NOT copy** — machine-bound, causes login weirdness:
`.qoder\installation_id`, `.qoder\.auth\`,
`com.qoder.app.stable\auth.v1.dat`, `com.qoder.app.stable\auth.machine-id`.
Sign in fresh on the new PC.

**Skip, pure bulk:** `Cache`, `Code Cache`, `GPUCache`, `logs` (171 MB), `Partitions`,
`Dawn*Cache`, `.qoder\.cache`, `.qoder\tmp`, `.qoder\shell-snapshots`.

## 3. Procedure

1. **Quit Qoder completely on the old PC** — including the tray icon. While it runs, the
   newest turns stay in the WAL and you copy a stale database.
2. Copy the list above.
3. On the new PC: install Qoder, sign in, launch once, quit.
4. Put the repo at **`D:\projects\Folio`** — same drive letter and path (see caveat).
5. Merge the two `.qoder` folders in, replace `main.sqlite*` in `com.qoder.app.stable`.
6. Launch, open `D:\projects\Folio`, the chat list should include this session.

### Caveat: the project folder name is derived from the repo path

`D--projects-Folio` clearly encodes `D:\projects\Folio`. That derivation is inferred from
the naming, not proven. If the chat list is empty on the new PC:

1. Open the project there once, so Qoder creates its key folder.
2. Look under `%USERPROFILE%\.qoder\projects\` for the new folder (e.g. `D--dev-Folio`).
3. Move the `.jsonl` files, their sidecar folders, and `memory\` into it.

That works whatever the exact rule is.

### Also worth checking first

Qoder is signed in with an account here. If session history syncs server-side, logging in
on the new PC may be all that's needed — try that before file surgery.

## 4. State of the code at the time of writing

`HEAD` = `ec823ae` (v1.0.18). **Everything below is uncommitted** — carry the working tree
or commit and push, or this work is lost even if the chat survives.

Modified:
`androidApp/.../MainActivity.kt`, `desktopApp/build.gradle.kts`, `desktopApp/.../Main.kt`,
`shared/src/androidMain/.../HtmlContentSurface.android.kt`,
`shared/src/commonJvm/.../Database.kt`, `shared/src/commonJvm/.../JdbcRepositories.kt`,
`shared/src/commonMain/.../ReaderSettings.kt`, `shared/src/commonMain/.../OverlayUi.kt`,
`shared/src/commonMain/.../PageEngine.kt`,
`shared/src/composeUi/.../ReaderScreen.kt`, `shared/src/composeUi/.../ReaderViewModel.kt`,
`shared/src/composeUi/.../theme/Theme.kt`,
`shared/src/desktopMain/.../HtmlContentSurface.desktop.kt`

New (untracked):
`shared/src/commonMain/kotlin/com/folio/reader/ui/render/ReaderCss.kt`,
`shared/src/desktopTest/kotlin/com/folio/reader/ThemeSchemeTest.kt`

### Done and green

- Layout mode is now read by the renderer and switchable in-reader (Scroll / Page / Spread /
  Focus) on both platforms.
- Both per-platform CSS builders replaced by one shared `ReaderCss`, which is what makes the
  previously-dead settings rows (paragraph / word / letter spacing, text width, heading ink,
  publisher fonts) reach the page.
- Per-book reader settings: scope-routed writes, local only, cloud schema untouched.
- 34 reader themes (8 new vibrant ones), both pickers derived from `Theme.PICKER` so no
  preset can go unselectable again; missing `Dark` app palette pack added.
- `ThemeSchemeTest` guards the theme/palette/pack registries. Full desktop suite: 127 tests,
  0 failures. Both shared targets compile.

### Open — this is where to resume

1. **Blank page in paged mode on Android.** Installed 1.0.13 release APK, opened
   *Omniscient Reader* and the page area painted nothing while the engine reported
   `ok:797:360:14:141` and the bar showed 6/14. So layout ran and painting failed. Old and
   new code pass identical args to `PageEngine.css(...)`, so this is likely the original
   #13 bug rather than a regression from the CSS consolidation. Not yet root-caused.
   `PageEngine.css` hides `body` with `opacity:0` and its own JS reveals it
   (`body.style.opacity='1'`) — check whether that reveal happens on the Android WebView
   path in paged modes.
2. Verify on PC in Page and Spread modes (same question, faster loop).
3. Verify per-book settings survive a relaunch, and that "All books" clears that book's
   override.
4. Verify each previously-dead settings row visibly moves the page, on both platforms.
5. `Theme.surface` is declared on all 34 presets and read by nothing. Left alone on purpose:
   overlays take the **app** palette by design (the earlier overlay-contrast fix). Decision
   pending: delete the field, or let floating panels follow the page theme.
6. 9 existing presets became selectable that previously weren't (`high_contrast`, the
   solarized / nord / gruvbox / dracula / monokai ports). Fine, or curate the list back down.

### Build commands on this project

```
./gradlew :shared:compileKotlinDesktop :shared:compileDebugKotlinAndroid   # compile gates
./gradlew :shared:desktopTest                                              # 127 tests
./gradlew :desktopApp:createDistributable                                  # exe image (default)
./gradlew :desktopApp:packageMsi                                           # only when asked for an MSI
./gradlew :androidApp:assembleRelease                                      # NEVER debug APK
```

- Never `packageReleaseMsi` — `proguardReleaseJars` fails. `packageMsi` works.
- Phone installs must be **release** builds; a debug APK conflicts by signature with the
  installed package. Phone stays versionName 1.0.13 / versionCode 13; version bumps are
  PC-only (`packageVersion` in `desktopApp/build.gradle.kts`, currently 1.0.19).
- Helpers: `build/kill_folio.ps1`, `build/maximize_folio.ps1`, `build/ui_helper.ps1 shot -Out <png>`.
- A fresh PC also needs: JDK 17+, Android SDK, and JCEF natives (Gradle fetches them).
