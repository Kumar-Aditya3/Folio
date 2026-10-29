# Offline narration

Android's existing narration panel supports three optional packs. Selecting a
model never downloads it or starts playback. Existing Phonon 31M installations
keep their original directory and installation marker.

| Model | Download | Installed assets | Access |
| --- | ---: | ---: | --- |
| Phonon 31M Q8 | 65.1 MB | 65.1 MB | Public, Apache-2.0 |
| Phonon 40M Q8 | 75.1 MB | 75.1 MB | Hugging Face terms and read token, CC BY-NC 4.0 |
| Kokoro 82M v1.0 INT8 | 132.4 MB | 189.9 MB | Public, Apache-2.0 model |

Kokoro includes its voice table and pronunciation data. The panel offers Heart,
Bella, Nicole, Michael, Emma and George; IDs are pinned to the v1.0 speaker table.
The pronunciation runtime/data includes GPL-covered eSpeak NG. Full notices and
source links are in `androidApp/src/main/assets/licenses/narration/`.

## Installation and credentials

`NarrationPacks` pins model revisions, byte lengths and SHA-256 digests. The two
non-LFS Phonon 40M metadata files use their pinned Git blob SHA-1 object IDs.
Downloads resume only with a matching Content-Range. A marker is published after
verification and atomic promotion; partial downloads cannot become installed
packs. Kokoro's pinned archive is extracted with path, type, entry count and
expanded-size checks. The compressed copy is removed after installation.
Verification can finish from complete cached files without a network connection;
Wi-Fi restrictions apply when bytes actually need downloading.

Phonon 40M requires the reader to accept the publisher's terms using their own
account. The token is held for that download only, never saved to preferences,
files, URLs or error messages, and sent only to `huggingface.co` over HTTPS. CDN
redirects do not receive it. Playback needs no token or network after installation.

## Playback and resource ownership

Model and voice changes serialize with media commands, stop native synthesis,
persist the heard checkpoint, then leave the book paused. Only one speech model
is open. All engines feed the same 24 kHz float PCM pipeline, listening clock,
source markers, database commits, MediaSession, audio focus and foreground service.
Speed is applied to playback so the existing presented-frame accounting remains
the source of reading/listening progress and goals.

Native callbacks block on a bounded queue rather than retrying every 5 ms.
Shutdown cancels the queue before joining the producer. Paused output waits on a
state signal rather than polling. AudioTrack notifications wake the nonblocking
writer as frames are consumed; a 100 ms fallback handles pre-roll and unusual
audio outputs. PCM frames reference their immutable callback array without a
second copy. Download progress publishes at most ten updates a second.

The model is released on stop, completion, a minute of pause, or Android memory
pressure while paused. Active screen-off playback retains its foreground service
and wake lock. The embedding sweep's existing narration interlock remains in use.

## Native builds

Run `python scripts/fetch_sherpa.py` to reproduce the checked-in runtime AAR. The
script verifies Sherpa 1.12.30 and retains only its ARM64/x86_64 JNI libraries.
The separate C/C++ entry points and upstream duplicate ONNX Runtime are excluded.
Android embeddings and Sherpa use Maven's ONNX Runtime 1.23.2; desktop retains its
existing runtime. Do not replace this with an arbitrary native `pickFirst` rule.
Sherpa's JNI callback looks up `invoke(float[]): Integer`. Keep the explicit
`KokoroNative.AudioCallback` class and its R8 rule: an invokedynamic lambda only
exposes the erased bridge and causes a native abort. `KokoroJniContractTest`
checks the JVM signature; the instrumentation test exercises the native call.

`python scripts/build_phonon.py --ndk <NDK> --optimized --jobs 1` builds a separate
ARM FP16/dot-product library with 16 KB alignment. It is selected before loading
only when `getauxval(AT_HWCAP)` advertises FPHP, ASIMDHP and ASIMDDP. Missing
capabilities or probe errors use the generic library. Both Phonon models share
this pinned runtime. Instruction support does not establish a measured speedup.

## Delivery and optional audio

Six additional Phonon narrators are pinned to Gradium's Apache-2.0 voice catalog
revision `0ee17205f254b5abc43b266c8a0f215565c352f7`. Each is a separate,
checksum-verified 256,136-byte opt-in download, shared by the two Phonon models.
Choosing a missing voice disables listening until it is installed; it does not
silently substitute another voice. Explicitly selecting `default` also calls
the native voice setter, since directory iteration order is not a voice policy.
Previews read one common passage, pause book playback and never earn listening
time or change a book's text position. The narrator descriptions come from the
publisher; they are not claims of a completed subjective listening panel.

Paragraph phrasing combines adjacent sentences only inside one source anchor,
up to 360 characters. Long clauses prefer punctuation before a word-boundary
split. Headings, ordinary paragraphs, dialogue and scene separators have distinct
additional pauses. Both options are exposed in the existing narration panel.
Original reader annotation, offsets, passage statistics and displayed text remain
the source of truth. A changed plan restores from the persisted source offset
when an old passage hash no longer matches.

Per-book pronunciations are literal, whole-word, case-insensitive and
longest-first. Substitutions do not cascade. Rules are limited to 100 per book;
spoken expansions are split back into bounded model input. Editing pauses
playback and invalidates that book's saved audio. Book deletion removes rules
and local audio through the Android deletion routes; database deletion also
removes the dictionary key for other callers.

Audio caching is off by default. Opting in retains 24 kHz mono PCM16 in private
no-backup storage with a selectable 64/128/256 MB limit. Files are keyed by model
revision, voice and corrected spoken text, written to temporary files, and
published only when complete. Headers, length and CRC are checked before cached
audio reaches playback. Partial or corrupted files cannot count as a hit; LRU
eviction bounds complete audio, with at most one bounded temporary passage.
A cache hit does not initialize the neural model. Disabling caching stops using
and adding audio; the separate clear control removes the retained files.

Charging preparation is also opt-in and requires caching. It queues only the
selected/paused chapter with charging and battery-not-low constraints. It uses
installed assets only, makes no progress/statistics writes, and is bounded by
eight minutes of work and twenty minutes of audio per job. Completed passages
survive cancellation. Playback, auditions and preparation share one inference
mutex; background work yields at native callbacks when playback requests it.
Changing a model cancels preparation. Speech inference also defers embedding
backfill passes and reduces image-cache allowances.

See [PERFORMANCE.md](PERFORMANCE.md) for Baseline Profile generation and sustained
physical-device measurements.

## Verification details

Focused desktop tests are under `com.folio.reader.narration`: source anchors and
statistics, installer integrity/resume/access/archive behavior, and light/dark
renders at 320/393 dp including 200% text. Android unit tests cover existing
reading and embedding work policies.

`NarrationPlaybackTest` uses real public models and writes fixture books. Run it
only in an isolated emulator, never against a reader's personal installation.
It explicitly opts into downloads, tests both engines with the screen off,
focus interruptions, pause/seek, resource release, notification dismissal and
persisted statistics. Its Kokoro case also changes models/voices and runs a real
MiniLM embedding after Sherpa loads to catch shared-runtime ABI conflicts.

Phonon 40M's actual inference requires legitimately gated model access. Tests
without those credentials can verify its manifest, access failures and shared
Phonon engine, but cannot establish 40M voice quality or real-device speed.

Validation on 27 October 2026 passed 34 Android unit tests, 52 focused desktop
tests and all eight isolated-emulator integration tests. The latter exercised
real Phonon 31M and Kokoro synthesis, an optional Phonon narrator, replay without
loading a model, charging constraints, pronunciation-aware preparation,
screen-off playback, audio focus, media controls, dismissal and persisted
progress. UI review rendered 48 light/dark, model and font-size combinations.
Library readiness and assignment checks passed; an existing shelf-seed test
still expects both books when its selected category contains only one
(`LibraryShelfSeedTest.kt:95`). Neither that test nor its seed implementation
was changed by this work.

The signed, R8-optimized 1.2.118 (172) release built successfully with the generated
Folio profiles. All three APK signatures verified. The ARM64 artifact is
64,232,475 bytes, includes a 33,285-byte compiled Baseline Profile plus metadata,
preserves the Sherpa/JNI callback and WorkManager ABI names, and contains one
shared ONNX runtime with both speech engines. No neural model weights are bundled.
Actual Phonon 40M inference and sustained physical-phone measurements remain
unverified; Pocket TTS and SoC-specific QNN acceleration remain later experiments.
