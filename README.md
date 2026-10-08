# Folio

A local-first reading app for EPUB books and manga, built with Kotlin Multiplatform and
Compose Multiplatform. One shared codebase drives an Android app and a Windows desktop app.

Folio works entirely offline. Your library, reading progress, highlights, and statistics live
in a local SQLite database. Cloud sync exists but is opt-in and off by default.

## Features

**Reading**
- EPUB 2 and 3 parsing with chapter-level HTML rendering
- Typography control: font family and size, line and paragraph spacing, text width, margins,
  alignment, hyphenation
- Highlights, bookmarks, and notes with long-press selection
- Scroll-position progress tracking that survives reflow after a typography change
- Theme packs with paired typefaces, plus in-reader brightness and clock

**Manga**
- Chapter reader with its own page-based chrome, separate from the EPUB reader
- Installs and runs Mihon/Tachiyomi extensions for online sources, including extension trust
  verification before load
- Background update checks for followed series via WorkManager

**Library and insights**
- Books and Manga tabs with filters for reading state, series, and collections
- Reading statistics: day streaks, session and binge averages, an activity heatmap filterable by
  content type, per-title finish predictions, and reading-pattern classification
- Tags, collections, and a quote browser over saved highlights

**Sync (optional)**
- Cross-device sync over the Firestore REST API with anonymous Identity Toolkit auth
- No Firebase SDK and no account system; a Web API key is the only credential
- Metadata-only by default; EPUB files and covers sync too if you configure a storage bucket

## Platform support

| | Android | Desktop |
| --- | --- | --- |
| Status | Primary target | Windows only |
| Minimum | Android 7.0 (API 24) | Windows 10 x64 |
| Built against | compileSdk 34 | JDK 17, JVM target 17 |

Desktop is Windows-only in practice, not by preference: the reader's HTML surface uses JCEF and
the build declares only `jcef-natives-windows-amd64`, while packaging targets MSI and EXE. The
desktop code *compiles* on Linux and macOS — CI does exactly that — but it will not run there
without adding the matching JCEF natives.


## Project layout

```
Folio/
├── shared/                     Kotlin Multiplatform module — most of the app lives here
│   └── src/
│       ├── commonMain/         Domain models, repositories, EPUB parser, sync engine
│       ├── commonJvm/          JVM-only shared code (JDBC storage, book importer)
│       ├── composeUi/          Shared Compose UI: home, library, reader, manga, settings, stats
│       ├── androidMain/        Android platform bindings + vendored manga extension runtime
│       ├── desktopMain/        Desktop platform bindings (JCEF HTML surface, file pickers)
│       └── desktopTest/        Unit tests, run on JVM
├── androidApp/                 Android entry point, navigation shell, DI graph, settings screens
│   └── src/androidTest/        Instrumented tests
├── desktopApp/                 Compose Desktop window, MSI/EXE packaging, same DI shape
├── docs/                       Design specs, dependency plan, troubleshooting
├── firestore.rules             Security rules for the optional sync backend
└── gradle/libs.versions.toml   Single source of truth for every dependency version
```

The `shared` module sets `kotlin.mpp.applyDefaultHierarchyTemplate=false` and wires its source
sets by hand, which is why `commonJvm` and `composeUi` exist as intermediate sets shared between
Android and desktop rather than the default hierarchy's `jvmMain`.

## Getting started

### Prerequisites

- **JDK 17.** This is what CI uses and what both modules target. Newer JDKs mostly work; note
  that AGP's bundled lint crashes on JDK 25, which is why `checkReleaseBuilds = false` is set in
  `androidApp/build.gradle.kts`.
- **Android SDK** with platform 34, for Android builds only.
- No Gradle install needed — use the committed wrapper.

Point Gradle at your SDK by creating `local.properties` in the repository root (it is gitignored):

```properties
# Forward slashes avoid Java properties escaping rules on Windows.
sdk.dir=C:/Users/you/AppData/Local/Android/Sdk
```

Android Studio writes this file for you when you open the project.

### Build and run

```bash
# Android debug APK -> androidApp/build/outputs/apk/debug/
./gradlew :androidApp:assembleDebug

# Install onto a connected device or running emulator
./gradlew :androidApp:installDebug

# Run the desktop app (Windows)
./gradlew :desktopApp:run

# Build a Windows installer -> desktopApp/build/compose/binaries/main/msi/
./gradlew :desktopApp:packageMsi
```

On Windows use `gradlew.bat` in place of `./gradlew`.

### Tests

```bash
# Shared unit tests (JUnit 5)
./gradlew :shared:desktopTest

# Instrumented Android tests — needs a connected device or emulator
./gradlew :androidApp:connectedAndroidTest
```

The instrumented suite runs against the app's real database in the app's own process and mutates
it deliberately, restoring state afterwards. Run it on a test device, not one holding a library
you care about.

## Configuration

Both configuration files below are gitignored. Committed `.example` templates document every
key, and the app runs fine with neither file present.

### Cloud sync

Optional. Create a Firebase project, enable Anonymous authentication and Firestore, then supply
the Project ID and Web API key one of three ways:

1. In the app, under **Settings → Advanced** — highest precedence, and the only way that needs no
   rebuild.
2. On desktop, in a `.env` file at the repository root. Copy `.env.example` to get started.
3. Via `FOLIO_FB_PROJECT_ID`, `FOLIO_FB_API_KEY`, and optionally `FOLIO_FB_STORAGE_BUCKET`
   environment variables, or on Android via `folio_fb_project_id` / `folio_fb_api_key` string
   resources.

Deploy `firestore.rules` to your project before syncing. The rules confine every document to the
authenticated user's own subtree, so an anonymous UID can only ever read and write its own data.

Sync stays inactive until an API key is present, so a fresh clone will not talk to any server.

### Release signing

Copy `keystore.properties.example` to `keystore.properties` and fill in your own keystore
details. Generate a keystore with:

```bash
keytool -genkeypair -v -keystore folio-release.jks -keyalg RSA -keysize 2048 -validity 10000 -alias folio
```

When `keystore.properties` is absent the release `signingConfig` is skipped entirely and debug
builds fall back to the standard debug keystore, so `assembleDebug` works on a clean clone.

## Continuous integration

`.github/workflows/ci.yml` runs on pushes to `main` and on every pull request, using JDK 17
(Temurin) on `ubuntu-latest`, split across two jobs:

- **desktop-tests** — `:shared:desktopTest` (the shared unit suite), then `:desktopApp:compileKotlin`
- **android-build** — `:androidApp:assembleDebug`

## Documentation

Design and implementation notes live in [`docs/`](docs/):

| Document | Contents |
| --- | --- |
| [`FOLIO_IMPLEMENTATION_SPEC.md`](docs/FOLIO_IMPLEMENTATION_SPEC.md) | Core architecture rules and testing strategy |
| [`FOLIO_VISUAL_SPEC.md`](docs/FOLIO_VISUAL_SPEC.md) | Design tokens, component rules, surfaces |
| [`FOLIO_MOTION_SPEC.md`](docs/FOLIO_MOTION_SPEC.md) | Animation and transition rules |
| [`FOLIO_TYPOGRAPHY_SPEC.md`](docs/FOLIO_TYPOGRAPHY_SPEC.md) | Type scale and typeface pairing |
| [`FOLIO_DEPENDENCY_PLAN.md`](docs/FOLIO_DEPENDENCY_PLAN.md) | Dependency choices and version policy |
| [`TROUBLESHOOTING.md`](docs/TROUBLESHOOTING.md) | Common build and runtime problems |

## Attribution

Folio's manga support is built on code from **[Mihon]** and its predecessor **[Tachiyomi]**, both
Apache-2.0. The vendored trees under `shared/src/androidMain/kotlin/` — `eu/kanade/`, `mihon/`,
`tachiyomi/`, and `logcat/` — provide the extension runtime (loading, trust verification, install
flow) and the HTTP source engine (OkHttp interceptors, Cloudflare and rate-limit handling, source
and filter models), adapted to Folio's dependency graph. Extension compatibility means Folio can
run existing Mihon extensions; it does not host or distribute any content itself.

The `logcat` package derives from **[square/logcat]**. Injekt is consumed as a binary dependency
from Mihon's fork. See [`NOTICE`](NOTICE) for full details.

[Mihon]: https://github.com/mihonapp/mihon
[Tachiyomi]: https://github.com/tachiyomiorg/tachiyomi
[square/logcat]: https://github.com/square/logcat

## Status

A personal hobby project, developed in the open. It is usable day to day — that is what it was
built for — but there is no release cadence, no support commitment, and APIs and schemas change
whenever it suits the author. Issues and pull requests are welcome; slow or absent responses are
likely.

## License

Licensed under the Apache License, Version 2.0. See [`LICENSE`](LICENSE) and [`NOTICE`](NOTICE).

```
Copyright 2026 The Folio Authors

Licensed under the Apache License, Version 2.0 (the "License");
you may not use this file except in compliance with the License.
You may obtain a copy of the License at

    http://www.apache.org/licenses/LICENSE-2.0

Unless required by applicable law or agreed to in writing, software
distributed under the License is distributed on an "AS IS" BASIS,
WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
See the License for the specific language governing permissions and
limitations under the License.
```
