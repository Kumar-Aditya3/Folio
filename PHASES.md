# Folio — Implementation Plan

## Tech Stack
- **Kotlin Multiplatform (KMP)** + **Compose Multiplatform**
- **Shared core**: `commonMain` (EPUB parsing, models, sync logic, database) + `commonJvm` source set for JVM-shared code (Android + Desktop)
- **Platform UI**: `androidMain` + `desktopMain` (Compose)
- **Database**: Raw JDBC (`org.xerial:sqlite-jdbc`) behind a small `Database` facade
- **Serialization**: kotlinx.serialization (JSON)
- **Networking**: `HttpURLConnection` against Firebase REST v1 (Firestore, Identity Toolkit, Storage); Android Firebase SDK deps exist but the sync path is REST. Ktor is declared in the version catalog but unused.
- **DI**: Manual constructor injection (`AppGraph` / `FolioDesktopAppDependencies`)
- **Coroutines/Flow**: KotlinX Coroutines
- **Testing**: JUnit (JVM), KotlinTest, Compose testing

---

## Project Structure
```
Folio/
├── build.gradle.kts                 # Root build config
├── settings.gradle.kts              # Module inclusion
├── gradle.properties                # Versions
├── PHASES.md                        # This file
├── shared/                          # KMP shared module
│   ├── build.gradle.kts
│   └── src/
│       ├── commonMain/kotlin/folio/
│       │   ├── model/               # Data models (Book, Position, Highlight, etc.)
│       │   ├── epub/                # EPUB parsing & extraction
│       │   ├── database/            # Raw JDBC schema + repositories
│       │   ├── sync/                # Sync engine, queue, conflict resolution
│       │   ├── search/              # Full-text search indexing
│       │   ├── statistics/          # Reading stats calculation
│       │   ├── settings/            # Settings models + preferences
│       │   ├── firebase/            # Firebase data models + mappers
│       │   └── util/                # Hashing, dates, file utils
│       ├── commonJvm/kotlin/folio/
│       │   ├── platform/            # JVM-shared platform helpers (FS abstraction, FontManager)
│       │   └── database/            # Raw JDBC Database facade + Jdbc* repositories
│       ├── androidMain/kotlin/folio/
│       │   └── platform/            # Android implementations (FS)
│       └── desktopMain/kotlin/folio/
│           └── platform/            # Desktop implementations (FS, window)
├── androidApp/                      # Android application
│   ├── build.gradle.kts
│   └── src/main/
│       ├── AndroidManifest.xml
│       ├── java/folio/app/          # App entry, DI, Firebase init
│       └── res/                     # Resources
└── desktopApp/                      # Desktop (JVM) application
    ├── build.gradle.kts
    └── src/main/
        ├── java/folio/app/          # App entry, DI, Firebase init
        └── resources/               # Icons, fonts
```

---

## Phase 1 — Offline Reader (Weeks 1–4)
**Goal**: Fully functional local EPUB reader with library, reading, progress, bookmarks, settings.

### 1.1 Project Setup (Days 1–2)
- [x] Initialize KMP project with Compose Multiplatform
- [x] Configure Gradle: KGP, Compose, manual repositories, Serialization
- [x] Set up Android + Desktop app modules
- [x] Configure `commonMain` source sets
- [x] Add shared dependencies (coroutines, serialization, datetime)

### 1.2 Data Models & Database (Days 3–5)
- [x] Define SQLite schema (Book, ReadingPosition, Bookmark, ReadingSession, Settings, Series, Collection, Tag, Highlight, Note)
- [x] Generate database drivers for Android/JVM (raw JDBC + sqlite-jdbc)
- [x] Implement `BookRepository`, `PositionRepository`, `SettingsRepository` + full repository layer
- [x] Add migration strategy (CREATE TABLE IF NOT EXISTS)

### 1.3 EPUB Parsing (Days 6–10)
- [x] Implement ZIP validation + container.xml reading
- [x] Parse OPF: metadata, manifest, spine
- [x] Parse NCX/NAV for TOC
- [x] Extract cover image
- [x] Normalize chapters (spine order + TOC hierarchy)
- [x] Calculate SHA-256 hash, word/character counts
- [x] Generate internal book ID (hash + metadata fallback)
- [x] Write unit tests with sample EPUBs (5 parser tests + corpus test)

### 1.4 Library & Import (Days 11–14)
- [x] Implement library directory management (`Library/books/<id>/`)
- [x] File picker (Android SAF / Desktop FileChooser)
- [x] Import flow: copy → parse → hash → DB → cover extract → index
- [x] Duplicate detection (ISBN > title+author > hash)
- [x] Grid/Compact library views with filters

### 1.5 Reader Core (Days 15–20)
- [x] Chapter rendering (HTML → Compose via HtmlRenderer + AnnotatedString)
- [x] CSS handling: user prefs (font family/size/line-height/margins)
- [x] Navigation: TOC sidebar, chapter list, progress slider
- [x] Precise position model: spineIndex + paragraph index + scroll fraction
- [x] Progress calculation: weighted by word count, per-device tracking

### 1.6 Reader Settings & Typography (Days 21–24)
- [x] Font family/size/weight/line-height/letter-spacing/margins
- [x] Themes: Paper, White, Sepia, Gray, Dark, OLED Black, High Contrast
- [x] Per-book overrides + presets
- [x] Formatting modes: Original / Hybrid / Normalized
- [x] Live preview in settings

### 1.7 Bookmarks & Progress Persistence (Days 25–27)
- [x] Bookmark model + CRUD
- [x] Auto-save position on meaningful change
- [x] Reading session tracking (start/pause/resume/end)
- [x] Progress display: book %, chapter %, session stats

### 1.8 UI Polish (Days 28–30)
- [x] Dashboard: library grid with progress/status badges
- [x] Book detail screen (tags, collections, series, notes)
- [x] Reader controls (auto-hide chrome, TOC sidebar, annotations sidebar)
- [x] Keyboard shortcuts (desktop) / long-press highlights (Android)
- [x] Accessibility: scalable text, high contrast

---

## Phase 2 — Personal Reading System (Weeks 5–8)
**Goal**: Highlights, notes, search, collections, series, statistics, heatmap.

### 2.1 Annotations (Days 31–36)
- [x] Highlight: text selection, colors, locator persistence
- [x] Note: attached to highlight/bookmark/chapter/book
- [x] Bookmark: label, locator
- [x] Tags: user-defined, on books + annotations
- [x] "Revisit" flag + global screen
- [x] Quote collection + browser
- [ ] Undo/Recently deleted

### 2.2 Full-Text Search (Days 37–41)
- [x] Chapter-level indexing (SQL Delimiter FTS5 with book-level FTS)
- [x] Search scopes: chapter, book, series, library, highlights, notes, bookmarks, quotes
- [x] Results with context snippets
- [x] Navigation from result to position

### 2.3 Collections & Series (Days 42–45)
- [x] Series model + manual ordering + fractional numbers
- [x] Collections (user-defined, many-to-many)
- [x] Library views filtered by series/collection

### 2.4 Statistics & Heatmap (Days 46–52)
- [x] Per-book: time, sessions, speed, dates, completion
- [x] Per-day/week/month/year aggregates
- [x] Reading patterns: avg session, streak, best day/hour, speed
- [x] GitHub-style heatmap calendar
- [x] Reading history timeline

### 2.5 Export/Import (Days 53–56)
- [x] Export annotations (Markdown, JSON, CSV)
- [x] Backup/restore library metadata + progress + annotations
- [x] Import annotations

---

## Phase 3 — Cloud Synchronization (Weeks 9–12)
**Goal**: Multi-device sync via Firebase (Auth, Firestore, Storage).

### 3.1 Authentication & Device Registry (Days 57–60)
- [x] Firebase Auth (Anonymous + Email/Password via REST Identity Toolkit)
- [x] Device registration (ID, name, platform, version)
- [x] Device registry (upsert/get/deactivate)

### 3.2 Sync Engine (Days 61–68)
- [x] Offline mutation queue (local DB → sync ops)
- [x] Push local changes → Fetch remote → Resolve conflicts → Apply
- [x] Idempotent sync (re-runnable)
- [x] Conflict resolution:
  - Scalars: last-write-wins (updatedAt)
  - Entities: merge (highlights, notes, bookmarks)
  - Position: latest updatedAt
- [x] Sync status UI (synced/syncing/error + details + per-device status)

### 3.3 Firestore Data Sync (Days 69–75)
- [x] Books, metadata, positions, highlights, notes, bookmarks
- [x] Reading sessions, statistics summaries
- [x] Settings (global + per-book)
- [x] Series, collections, tags
- [x] Push/pull with LWW merge semantics + per-device echo filtering

### 3.4 Cloud Storage (Days 76–82)
- [x] EPUB upload/download (via REST Firebase Storage)
- [x] Cover images
- [x] Storage states: LOCAL_ONLY, UPLOADING, SYNCED, REMOTE_ONLY, SYNC_ERROR
- [x] Storage manager: local size, remove local copy, keep cloud

### 3.5 EPUB Replacement & Migration (Days 83–84)
- [x] Detect same book / different hash
- [x] Position mapping: chapter ID → title → nearby text → percentage

---

## Phase 4 — Advanced Reader (Weeks 13–16)
**Goal**: Font management, normalization, footnotes, advanced typography, rereads.

### 4.1 Font Management (Days 85–89)
- [x] Font import (TTF/OTF) → app fonts directory
- [x] Font list in settings with preview
- [x] EPUB embedded font toggle

### 4.2 Advanced Typography (Days 90–95)
- [x] Hyphenation (language-aware)
- [x] Justification controls
- [x] Widow/orphan handling
- [x] Chapter heading typography separate from body
- [x] Variable font weight support

### 4.3 Footnotes & Links (Days 96–100)
- [x] Internal link handling (footnotes, cross-refs)
- [x] Desktop: hover popup
- [x] Android: bottom sheet / inline expand
- [x] External links → system browser
- [x] Position memory before following links

### 4.4 Reread Support (Days 101–104)
- [x] Reading cycles (Read #1, #2, #3)
- [x] Per-cycle: start/finish, time, sessions, progress history
- [x] Permanent annotations shared across cycles

### 4.5 Advanced Export/Import (Days 105–108)
- [x] Full backup file (JSON + EPUBs optional)
- [x] Selective export (book + annotations)
- [x] Migration between app versions

---

## Phase 5 — Polish (Weeks 17–20)
**Goal**: Production readiness.

### 5.1 EPUB Compatibility (Days 109–114)
- [x] Malformed EPUB handling (graceful degradation)
- [x] Unsupported feature warnings
- [x] SVG, audio/video placeholders
- [x] Large book performance (lazy chapter loading)

### 5.2 Performance (Days 115–119)
- [x] EPUB parse caching
- [x] Search index optimization
- [x] Image lazy loading + decoding
- [x] Database query optimization
- [x] Startup time < 500ms

### 5.3 Accessibility (Days 120–124)
- [x] Screen reader support (TalkBack/JAWS/NVDA)
- [x] Keyboard navigation (all actions)
- [x] Minimum touch targets
- [x] System font scaling
- [x] Reduced motion

### 5.4 Edge Cases & Error Handling (Days 125–130)
- [x] Corrupted files, missing resources
- [x] Sync conflicts UI
- [x] Offline queue overflow
- [x] Storage full handling
- [x] Crash recovery (position persistence)

### 5.5 Platform Polish (Days 131–136)
- [x] Android: share sheet import, wake lock, backup API
- [x] Desktop: multi-window, system tray, file associations
- [x] macOS preparation (KMP iOS target)

---

## Key Technical Decisions

| Area | Decision |
|------|----------|
| EPUB rendering | HTML → Compose via XmlPullParser + AnnotatedString |
| Database | Raw JDBC (sqlite-jdbc) with FTS5 for search |
| Sync | Custom offline queue + Firestore REST v1 |
| Position locator | spineIndex + paragraph index + scroll fraction |
| Hashing | SHA-256 via platform `MessageDigest` |
| Image loading | Cover extraction at import time |
| Font loading | FontManager + platform fonts directory + FontFamily API |
| Settings | JSON blobs in SQLite (`global_reader_settings` / `book_reader_settings:<id>`) |
| Custom fonts | FontManager with TTF/OTF import, platform fonts directory |
| Link handling | Internal links → chapter navigation, external → system browser |
| Accessibility | Semantics properties, minimum touch targets, keyboard shortcuts |
| Error handling | EpubParseResult sealed class with Success/PartialSuccess/Failure |
| Backup | ExportManager with JSON backup files |

---

## Testing Strategy
- **Unit**: Shared logic (parsers, sync, stats, search) on JVM
- **Integration**: Database repos, repositories on Android/JVM
- **UI**: Compose testing (shared + platform)
- **E2E**: Manual test matrix per release
- **Sample EPUBs**: Maintain test corpus (simple, complex, malformed, RTL, vertical)

---

## Definition of Done per Phase
- All features compile on Android + Desktop
- Unit tests pass (shared logic >80% coverage)
- Manual verification on both platforms
- No critical sync/data-loss bugs
- Performance targets met