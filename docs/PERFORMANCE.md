# Performance verification

## Changes and limits

The window-level motion provider observes START/STOP and the system animator
scale. Hidden windows stop ambient clocks. An opaque reader also suppresses the
underlying silk field. These paths retain the existing static appearance.

Statistics, quotes, exclusions and the library's hoisted book/collection feeds
warm once. Their StateFlows retain the last result; visible screens acquire a
reference-counted lease to resume upstream queries. Returning screens paint the
cached result immediately and refresh from the database. Readiness is still
derived from the shared result, avoiding a second statistics subscription.

Book covers and accent sampling use one decode path. Manga covers use striped
locks to deduplicate identical requests while permitting unrelated network
loads. Accent observers wait for a cache notification rather than polling.
Image caches share a heap-sensitive allowance: one eighth each for book and
manga covers, half for decoded pages and one quarter for encoded manga pages.
Speech or low-RAM devices reduce the allowance and manga prefetch. Page decode
also respects a pixel limit. Visible ImageBitmap references are never recycled.
Android's trim-memory callback drops cache references. Reader WebViews already
destroy themselves on AndroidView release; this change adds no retained pool.

These are resource policies, not measured percentage improvements. Native model
and WebView allocations must be included when evaluating a physical device.

## Baseline Profiles

The `baselineprofile` module uses the AndroidX Baseline Profile 1.4.1 Gradle plugin
(compatible with this project's legacy AGP/Kotlin DSL on Windows) and 1.5.0 runtime
workflow. Startup has its own startup profile. The other journey imports sixteen
small EPUB fixtures with covers, visits Stats/Home/Library, scrolls the library,
opens a book and scrolls its real HTML reader.

Generate in an **isolated emulator**, never a personal installation containing
books. The journeys import test books. The default managed device is API 30 AOSP;
AGP 9.2 calls its device container `localDevices`.

```powershell
./gradlew.bat :androidApp:generateReleaseBaselineProfile -PfolioEmulator --max-workers=1
```

For an already running isolated emulator, select it explicitly with
`ANDROID_SERIAL`, add `-PfolioProfileConnected`, and run the same task. Keep the
generated text profiles in the app source tree. Normal release assembly consumes
them without launching an emulator or regenerating them.

On a host with insufficient RAM to run Gradle and the emulator together, first
assemble `:androidApp:assembleNonMinifiedRelease` and
`:baselineprofile:assembleNonMinifiedRelease` with `-PfolioEmulator`, stop Gradle,
then install those two APKs on the isolated emulator. An API 30 AOSP emulator
must allow `adb -s SERIAL root`. Run the collector directly:

```powershell
adb -s SERIAL shell am instrument -w -r -e androidx.benchmark.enabledRules BaselineProfile -e class com.folio.reader.baselineprofile.BaselineProfileGenerator com.folio.reader.baselineprofile/androidx.test.runner.AndroidJUnitRunner
adb -s SERIAL pull /sdcard/Android/media/com.folio.reader.baselineprofile/BaselineProfileGenerator_startup-startup-prof.txt startup-prof.txt
adb -s SERIAL pull /sdcard/Android/media/com.folio.reader.baselineprofile/BaselineProfileGenerator_libraryStatisticsAndReader-baseline-prof.txt library-baseline-prof.txt
python scripts/merge_baseline_profiles.py --baseline library-baseline-prof.txt --startup startup-prof.txt
```

The merge retains the union of hot/startup/post-startup flags for each rule and
includes startup rules in the Baseline Profile too. Only merge output from
successful runs against the current source; this does not generate substitute
rules or benchmarks.

`FolioBenchmarks` compares cold startup without compilation against a required
Baseline Profile and records library/reader frame times. Use a physical device
for performance comparisons; an emulator can validate the journeys and profile
generation but cannot establish user-facing speed or battery improvements.

On 27 October 2026, both journeys completed on the isolated API 30 AOSP
emulator: startup stabilized after six passes and library/statistics/HTML-reader
navigation after twelve. The generated source profiles contain 22,950 startup
rules and 35,109 merged Baseline Profile rules. A five-second idle harness smoke
run also exported valid PSS/battery CSV and the raw diagnostic snapshots. These
checks establish that collection/export work, not a device performance gain.

Android guidance:
https://developer.android.com/topic/performance/baselineprofiles/create-baselineprofile
https://developer.android.com/jetpack/androidx/releases/benchmark

## Sustained device scenarios

Open the same book or library, use the same release/settings/brightness/thermal
starting state, then run one scenario per experiment:

```powershell
python scripts/measure_android_performance.py --serial SERIAL --scenario idle
python scripts/measure_android_performance.py --serial SERIAL --scenario scroll
# Start narration in the app first. Default: 30 minutes, screen off.
python scripts/measure_android_performance.py --serial SERIAL --scenario narration
```

The harness never clears app data or resets battery history. It records main-app
PSS, CPU snapshots, frame timings, audio output counters, battery, thermal state,
media sessions and process associations every thirty seconds. It wakes the
screen again after the narration scenario, including cancellation. Other apps'
audio or WebView processes must not be attributed to Folio. Inspect activity
process associations before adding a renderer's PSS to the main process. USB
charging, radio activity and unsupported charge counters limit battery claims;
use wireless ADB for a controlled discharge test where possible.

Compare Phonon default/narrators, Kokoro, cached replay and uncached synthesis.
Record time to first presented audio, AudioTrack underruns, screen-off progress,
peak/steady total PSS, CPU time, thermal throttling and actual charge loss.
No thirty-minute physical-phone result is implied by passing the automated tests.
