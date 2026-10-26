# Offline book narration

Android supports Phonon 31M English narration for reflowable EPUB text. The feature is unavailable on 32-bit processes; Folio keeps its existing ARMv7 app support. Desktop, PDFs and manga are outside this implementation.

## Use

Open **More > Narration** or **Listen** in the reader tools. Choose **Download English narration** to install the 65,051,089-byte pack (65.1 MB / 62.0 MiB). Opening Folio or the panel makes no model request. Wi-Fi-only is offered; Cancel preserves safe partial downloads for an explicit Retry. Downloads verify pinned SHA-256 digests and publish an installed marker only after native validation. Remove deletes pack assets, retaining book history/checkpoints.

Downloading does not play anything. **Listen from here** starts at the visible source block; the selection menu can start at a selected paragraph. Playback has previous/next passage, Pause, Stop, speed, Follow text and a sleep timer. Listening continues through screen-off and navigation, using an Android media session and notification. Headset commands use the same controller as the reader and mini-player. External audio interruptions pause narration; only transient focus loss can automatically resume it.

Swipe either compact player to the right to stop narration and remove it. Short or canceled drags return to rest; the dismissal follows Folio's motion preferences and exposes an accessibility Dismiss action. Swiping away the Android notification also uses the normal stop-and-save path, including during preparation. Android controls when a foreground media notification can be swiped; on devices that keep active playback pinned, pause it first. Dismissal retains book progress and the downloaded voice pack.

Manual scrolling suspends Follow text. **Return to narration** restores following. Pause retains narration's resume ownership. **Stop and continue reading** transfers the heard position to the visual reader. Browsing another location does not seek speech or credit skipped text.

## Runtime and persistence

The app includes a CPU-only native library, built from `gradium-ai/xn-ptts` revision `0418e21963f646e66f5b27d6c1aa5740524ec545`; the optional Q8 pack is `gradium/phonon-31m-en` revision `92741c5b07d085ff840e26fea8ce43110b87852f`. Notices ship in Android assets. No model is bundled with the APK. Model assets live in app-private, backup-excluded storage.

Native generation runs separately from playback. Source passages preserve short fragments and source order, with long sentences bounded at 360 characters. A one-second initial source-audio buffer provides some startup headroom. Streaming output is divided into 1,920-sample / 80 ms pieces. A 128-slot queue bounds queued PCM to approximately 10.2 seconds / 0.98 MB, plus output and native buffers. Pausing retains the bounded queue briefly; after 60 seconds the runtime releases it. Stop, completion and errors release resources. Background embedding passes yield while narration owns playback.

Only presented AudioTrack frames earn listening time. Speed adjusts source consumption, while goals receive actual listening time. Buffering, preparation, queue generation and paused time earn nothing. Reading/listening overlap is suppressed by a shared process clock and position ownership. Follow-along time requires recent actual visual interaction; programmatic scrolling supplies no evidence.

The narrator atomically commits checkpoint, shared book position, book progress and measured listening span. Local checkpoints occur about every five seconds, with cloud changes throttled to boundaries and roughly once a minute. Spans split at pause, seeks, daily boundaries and sync boundaries while retaining a logical sitting id. Home/goals/streaks consume the existing session totals; Statistics additionally shows listening and follow-along totals and keeps visual WPM separate. Civil timezone attribution is retained on new listening spans. Historical visual rows keep their original interpretation.

Cloud session payloads include optional versioned activity metadata. An independently keyed narration evidence document retains the same payload session id, allowing old clients to upsert one compatibility row and new clients to recover modality after a legacy rewrite. New clients deduplicate evidence and accept later cumulative totals. Live Firebase round trips still require validation against the user's deployment. Overlap suppression is process-local; concurrent activity on different devices is still summed by the existing aggregates. Cross-device interval union remains future work.

Source progress advances at presented passage boundaries. Retained playback resumes at the exact audio frame. Cold recovery conservatively restarts an incomplete passage rather than skipping its tail; it restores paused and never starts sound on launch. The current source block is outlined; there are no claimed word timestamps or invented audiobook duration.

## Build and verification

Prebuilt generic ARM64 and x86_64 libraries are included. Rebuild with:

```powershell
python scripts/build_phonon.py --ndk <Android-NDK-r27-path> --abi arm64-v8a
```

The script fetches pinned source, uses Cargo.lock, and requests 16 KB ELF segment alignment. It does not download model weights. Normal Android builds retain ARM64/ARMv7 splits; `-PfolioEmulator` selects the isolated x86_64 test variant.

Tests live in `shared/src/desktopTest/kotlin/com/folio/reader/narration`, the narration case in `SyncEngineTest`, and `androidApp/src/androidTest/java/com/folio/reader/narration/NarrationPlaybackTest.kt`. The instrumented test explicitly opts into the real model download and must run only in an isolated test installation. Render fixtures cover 320/393 dp, light/dark, installation/playback states and 200% text at the narrow width.

No connected physical phone was used during implementation. Emulator tests cannot establish phone narration quality, sustained real-time headroom, battery drain or thermal behavior. Before release, audition actual book passages and run at least a 30-minute ARM64 phone session, including focus loss, calls, Bluetooth/headset changes, recents removal, seeking and process recovery. Measure time to first presented audio, synthesis/audio ratio, underruns, process/native peak memory, temperature and battery drain. Generic CPU performance must be measured before promising smooth playback at higher speeds.

## Verification results ? 26 October 2026

- Android debug and instrumentation APK builds passed. The selected desktop suite passed **80 tests** (narration, sync, statistics, reading experience and embedding pacing).
- Twelve narration panel renders cover light/dark, 320/393 dp, not-installed/downloading/installed states, with 200% text at 320 dp. The rendered panels were inspected; hidden fallback dialogs and narrow confirmation actions were corrected. The mini-player reserves its measured height in the app inset contract.
- The real pinned pack downloaded and validated through the production opt-in installer in an isolated Android 36 x86_64 emulator. The native playback instrumentation test passed screen-off listening, media notification presence, transient focus interruption/recovery, pause time exclusion, forward/backward seeking while paused, durable listening/progress and Stop cleanup.
- On that **2-vCPU / 1.5 GB emulator**, the tested start reached presented audio after **8.55 seconds** and credited **9.97 seconds of audio during a 12-second screen-off observation**. This demonstrates functionality and also shows buffering on this constrained emulator. These are not ARM64 phone performance results or a claim of gap-free narration. Native phone latency, sustained throughput, heat and battery remain unmeasured.
- Full-app emulator runs crashed the host QEMU process (`0xc0000005`) while rendering the existing app UI. The runtime test therefore uses a minimal Android activity to isolate audio; it does not establish the reader's interactive device layout or GPU performance. Shared UI render checks ran separately.
- Live Firebase compatibility, cross-device interval union and physical-phone reader/headset/Bluetooth QA remain outside the verified scope. New listening spans share a logical sitting id across pauses/seeks/day splits; an existing visual session and a later narration session can still appear as separate sittings.

Final offline verification also passed **two Android tests** with Wi-Fi/mobile data disabled and `Active default network: none`: actual native speech, Android media-key Pause, notification, focus recovery, paused seeks and persistence, plus the merged manifest's private service entry. The service accepts trusted system media-session controls; its component is private to Folio (Android Auto service discovery is not in this scope). Pending service commands are canceled on service destruction, and a restored paused mini-player can be dismissed without creating a service.

The compact-player dismissal update passed **four offline Android tests**: the existing playback/private-service tests plus right-swipe, short/left/canceled drag, child-tap and accessibility dismissal checks. The playback test sends the actual notification delete intent and verifies that the service and notification disappear while the listening checkpoint remains.

The final offline run overlapped a host build and recorded 13.12 seconds to first presented audio and 4.86 seconds credited in the 12-second playback observation. Host contention makes that unsuitable as a phone benchmark, but reinforces that sustained smoothness has not been established. Functional assertions passed; throughput is a separate release-validation item.
