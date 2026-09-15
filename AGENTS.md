# AGENTS.md

Notes for agents working in this repository.

## Build & test commands

Gradle wrapper is `gradlew.bat` (PowerShell on Windows). **Always stop the
Gradle daemon as part of the same command** — do not leave daemons running
after a build or test run.

- Compile the shared module (desktop target):
  `.\gradlew.bat :shared:compileKotlinDesktop --console=plain; .\gradlew.bat --stop`
- Run desktop unit tests:
  `.\gradlew.bat :shared:desktopTest --console=plain; .\gradlew.bat --stop`
- Compile the Android app (needed when `androidApp/` sources changed):
  `.\gradlew.bat :androidApp:assembleDebug --console=plain; .\gradlew.bat --stop`

Cold compiles of `:shared:compileKotlinDesktop` can take 10+ minutes; give the
command a generous timeout. Chain the follow-up `--stop` with `;` so the daemon
is stopped even when the build task fails (PowerShell `;` runs the next command
regardless of the previous exit code).
