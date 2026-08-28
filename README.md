# Folio — Personal EPUB Reader

A local-first, cross-platform EPUB reading application with Firebase synchronization.

## Features

- **Offline-first**: Read entirely offline, sync when online
- **Cross-platform**: Android + Desktop JVM (Windows/Linux/macOS via the same desktop target; no native Apple target yet)
- **Precise reading positions**: spineIndex + paragraph index + scroll fraction locators
- **Rich annotations**: Highlights, notes, bookmarks, quotes, revisit items
- **Full-text search**: Across entire library
- **Reading statistics**: Sessions, speed, heatmap, patterns
- **Series & Collections**: Organize your library
- **Customizable typography**: Fonts, themes, layouts, per-book overrides
- **Firebase sync**: Anonymous/Email auth + Firestore metadata sync via REST v1 (works with just projectId + apiKey); EPUB file sync additionally requires a configured `storageBucket` and stays inactive otherwise

> **Status**: Advanced typography, pagination, and precise character-level selection highlights are partial or planned. Highlights currently capture whole paragraphs and there is no paginated layout engine yet.

## Architecture

```
Folio/
├── shared/              # Kotlin Multiplatform shared module
│   ├── commonMain/      # Shared business logic
│   ├── commonJvm/       # JVM-shared implementations (Android + Desktop)
│   ├── androidMain/     # Android implementations
│   └── desktopMain/     # Desktop (JVM) implementations
├── androidApp/          # Android application
└── desktopApp/          # Desktop (JVM) application
```

### Tech Stack

- **Kotlin Multiplatform (KMP)** + **Compose Multiplatform**
- **Raw JDBC** (`org.xerial:sqlite-jdbc`) behind a small `Database` facade for persistence
- **kotlinx.serialization** for JSON
- **HttpURLConnection** against Firebase REST APIs (Firestore REST v1, Identity Toolkit) — no native SDK dependency for sync; Android SDK deps exist in the Android module but the sync path is REST
- **Manual constructor injection** (`AppGraph` on Android, `FolioDesktopAppDependencies` on Desktop)
- **Coil** for image loading
- **Firebase** (Auth, Firestore; optional Storage for EPUB file sync when `storageBucket` is configured)

## Building

### Prerequisites

- JDK 21+
- Android SDK (for Android)
- Gradle (wrapper will be generated)

### Generate Gradle Wrapper

```bash
gradle wrapper
```

### Build Commands

```bash
# Build all
./gradlew build

# Build shared module (desktop JVM target)
.\gradlew.bat :shared:compileKotlinDesktop

# Build Android debug APK
./gradlew :androidApp:assembleDebug

# Install desktop distribution (run with ./gradlew :desktopApp:run)
./gradlew :desktopApp:installDist

# Package desktop fat jar
./gradlew :desktopApp:jar
```

### Run Desktop App

```bash
./gradlew :desktopApp:run
```

## Project Structure

### Shared Module (`shared/src/commonMain/kotlin/com/folio/reader/`)

```
model/           # Data models (Book, Chapter, ReadingPosition, etc.)
epub/            # EPUB parsing (EpubParser, ParsedEpub)
database/        # Raw JDBC schema + repository implementations
sync/            # Sync engine, queue, conflict resolution
search/          # Full-text search indexing
statistics/      # Reading statistics calculation
settings/        # Reader settings, themes, typography
firebase/        # Firestore models & mappers
importer/        # Book import flow
ui/
  ├── library/   # Library view models
  ├── reader/    # Reader view models
  └── statistics/# Statistics view models
util/            # Hashing, JSON utils
platform/        # Expect/actual platform abstractions
di/              # Manual DI graph wiring (AppGraph / FolioDesktopAppDependencies in app entry points)
```

### Platform Implementations

- **Android**: `shared/src/androidMain/` - File system, platform helpers (Firebase SDK deps present, but sync uses REST)
- **Desktop (JVM)**: `shared/src/desktopMain/` (+ JVM-shared code in `commonJvm`) - File system, JSON settings, JDBC SQLite

## Phase 1 Implementation Status

- [x] Project structure & Gradle configuration
- [x] SQLite database schema (raw JDBC, `CREATE TABLE IF NOT EXISTS`)
- [x] Core data models (Book, Chapter, ReadingPosition, Annotations)
- [x] EPUB parser (container.xml, OPF, NCX/NAV, cover extraction)
- [x] Platform abstractions (FileSystem, HashUtil, SettingsStore)
- [x] Repository interfaces & raw JDBC implementations
- [x] Book importer (hash, parse, copy to library, index)
- [x] ViewModels (Library, Reader, Statistics)
- [x] Sync engine skeleton
- [x] Manual constructor-injection graph (AppGraph / FolioDesktopAppDependencies)
- [ ] Android UI (Compose)
- [ ] Desktop UI (Compose)
- [ ] Reader component (HTML rendering, pagination)
- [ ] Settings UI
- [ ] Firebase integration

## Configuration

### Gradle Properties (`gradle/libs.versions.toml`)

```toml
kotlin = "2.0.20"
composeMultiplatform = "1.6.11"
sqliteJdbc = "3.46.1.0"
coil = "2.7.0"
```

(Koin/Ktor entries remain in the catalog but are unused; dependency wiring is manual.)

### Firebase Setup

1. Create Firebase project
2. Add Android app with package `com.folio.reader`
3. Add Desktop app (use same package)
4. Enable Authentication (Anonymous, Email/Password) - used via the Identity Toolkit REST API (no Google sign-in)
5. Enable Firestore Database
6. Enable Cloud Storage only if you want EPUB file sync (requires setting `storageBucket`; metadata sync works with projectId + apiKey alone)
7. Add `google-services.json` to `androidApp/`
8. Configure Firestore rules (see `firestore.rules`)

## Firestore Rules

```javascript
rules_version = '2';
service cloud.firestore {
  match /databases/{database}/documents {
    match /users/{uid} {
      allow read, write: if request.auth != null && request.auth.uid == uid;
      
      match /books/{bookId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
        
        match /positions/{deviceId} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
        
        match /highlights/{highlightId} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
        
        match /notes/{noteId} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
        
        match /bookmarks/{bookmarkId} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
        
        match /readingCycles/{cycleId} {
          allow read, write: if request.auth != null && request.auth.uid == uid;
        }
      }
      
      match /readingSessions/{sessionId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /settings/{settingsId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /tags/{tagId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /collections/{collectionId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /series/{seriesId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /quotes/{quoteId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /revisitItems/{itemId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /devices/{deviceId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
      
      match /syncState/{stateId} {
        allow read, write: if request.auth != null && request.auth.uid == uid;
      }
    }
  }
}
```

## License

MIT