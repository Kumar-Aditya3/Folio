# AGENTS.md

Notes for agents working in this repository.

## Build & test commands

Gradle wrapper is `gradlew.bat` (PowerShell on Windows). **The wrapper stops
the Gradle daemon by itself after every run** — do not chain a separate
`--stop`, and do not leave daemons running after a build or test run.

- Compile the shared module (desktop target):
  `.\gradlew.bat :shared:compileKotlinDesktop --console=plain`
- Run desktop unit tests:
  `.\gradlew.bat :shared:desktopTest --console=plain`
- Compile the Android app (needed when `androidApp/` sources changed):
  `.\gradlew.bat :androidApp:assembleDebug --console=plain`
- Build a release APK (signed via `keystore.properties`, output at
  `androidApp/build/outputs/apk/release/androidApp-release.apk`):
  `.\gradlew.bat :androidApp:assembleRelease --console=plain`

Cold compiles of `:shared:compileKotlinDesktop` can take 10+ minutes; give the
command a generous timeout. (Running `gradlew --stop` explicitly is still fine —
the wrapper detects it and does not stop twice.)
