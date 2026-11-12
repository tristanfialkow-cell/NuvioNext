# Nuvio Next — Milestone 1 technical audit

Audited checkout: `dev` @ `546c9d1` ("Add files via upload"), 2026-11-12.
Labels: **verified** = read in the repository or observed from a command run here; **inferred** = reasoned
from verified evidence; **unknown** = not determinable from this checkout.

Commands run for this audit are listed in [§11](#11-commands-run-and-results). No device or emulator was
available (the sandbox has no `/dev/kvm`), so nothing here is verified on Android hardware.

---

## 1. Architecture and module/dependency map

| Module | Kind | Notes |
|---|---|---|
| `:app` | `com.android.application` | All product code. Namespace `com.nuvio.tv`. **verified** (`settings.gradle.kts`, `app/build.gradle.kts`) |
| `:baselineprofile` | `com.android.test` | Baseline-profile generator + startup/frame macrobenchmarks, `targetProjectPath=":app"`, full flavour, minSdk 28. **verified** |
| `:ffmpeg-decoder-downmix` | Android library (CMake/JNI) | Only used when `useLocalFfmpegDecoder` / `USE_LOCAL_FFMPEG_DECODER` is set; otherwise the prebuilt `lib-decoder-ffmpeg-release.aar` is used. **verified** (`app/build.gradle.kts` dependencies block) |

Toolchain (**verified**): Gradle 8.13 wrapper, AGP 8.13.2, Kotlin 2.3.0, compile/target SDK 36,
**minSdk 24**, `ndkVersion = "29.0.14206865"`, Java/Kotlin target 11, core-library desugaring on. CI uses
JDK 17; this workspace used Temurin 21 successfully.

Layering inside `:app` (**verified** by package layout): `ui/` (Compose + TV Material 3 screens and
ViewModels) → `domain/` (models, repository interfaces) → `data/` (Retrofit/Moshi DTOs, mappers,
repositories, DataStore) and `core/` (player, torrent, debrid, plugin, sync, network, DI with Hilt).
Flavour source sets `src/full` and `src/playstore` swap the plugin runtime and updater (§4).

Key external dependencies (**verified**, `app/build.gradle.kts`, `gradle/libs.versions.toml`): Compose BOM
2026.05.01, TV Material, Hilt, Retrofit/OkHttp/Moshi (232 `@JsonClass(generateAdapter = true)` DTOs) and
Gson, kotlinx serialization, Coil, Navigation Compose, DataStore, Supabase + Ktor, Sentry, Media3 (stock
modules for HLS/DASH/RTSP/session/UI etc.), `mpv-android-lib:0.1.12`, NanoHTTPD, ZXing, Haze. Full-only:
QuickJS AAR, Jsoup, Jackson, NiceHttp, Conscrypt, CloudStream library, crypto-js.

**Media3 is a local fork (verified):** `configurations.all { exclude(group = "androidx.media3", …) }`
removes stock `media3-exoplayer/-common/-datasource/-datasource-okhttp/-exoplayer-hls/-extractor` and the
app ships `app/libs/lib-{common,exoplayer,exoplayer-hls,datasource,datasource-okhttp,extractor,ui}-release.aar`
instead. `androidx.media3.common.NuvioEngineConfig` (in `lib-common-release.aar`) toggles native allocation
(`DefaultAllocatorNative`, `libmedia3_exoplayer_jni.so`). The Gradle comment still refers to a
`:nuvio-exoplayer-engine` module that no longer exists (stale comment, **verified**).

## 2. Native dependency inventory, provenance and ABI support

All rows **verified** by listing AAR contents (`unzip -l`) and the packaged APK (`readelf -lW`).

| Library (`.so`) | Source | Language / provenance | ABIs | arm64 LOAD align |
|---|---|---|---|---|
| `libnuvio_engine.so` | `app/libs/lib-nuvio-engine-android-0.1.6.aar` | C/C++: libtorrent **2.0.12**, Boost.Asio, OpenSSL **3.5.7** (static), libc++ static; NDK clang 21. No source in repo. | 4 | 16 KB |
| `libquickjs.so` | `quickjs-kt-android-1.0.5-nuvio.aar` (full only) | C (QuickJS) + `com.dokar.quickjs` binding, Nuvio-patched build | 4 | 16 KB |
| `libffmpegJNI.so` | `lib-decoder-ffmpeg-release.aar` (or `:ffmpeg-decoder-downmix`) | C/C++ FFmpeg audio decoder | 4 | 16 KB |
| `libgav1JNI.so` | `lib-decoder-av1-release.aar` | C++ libgav1 | 4 | 16 KB |
| `libmpeghJNI.so` | `lib-decoder-mpegh-release.aar` | C++ MPEG-H | 4 | 16 KB |
| `libiamf.so`, `libiamfJNI.so` | `lib-decoder-iamf-release.aar` (full only) | C/C++ IAMF | 4 | **4 KB** (`libiamf.so`) |
| `libmedia3_exoplayer_jni.so` | `lib-exoplayer-release.aar` (Media3 fork) | C++ native allocator | 4 | 16 KB |
| `libasskt.so` | `nuvio-libass-release.aar` | C libass + Kotlin binding | 4 | 16 KB |
| `libmediainfo.so` | `nextlib-mediainfo-local.aar` | C++ MediaInfo | 4 | 16 KB |
| `libmpv.so`, `libplayer.so`, `libav*.so`, `libsw*.so`, `libxml2.so`, `libc++_shared.so` | `mpv-android-lib` (Maven) | C/C++ mpv + FFmpeg | 4 | 16 KB |
| `libconscrypt_jni.so` | Conscrypt (Maven, full only) | C++ BoringSSL | 4 | **4 KB** |
| `libsentry*.so`, `libdatastore_shared_counter.so`, `libandroidx.graphics.path.so` | Maven | C/C++ | 4 | 16 KB |
| `libdovi_bridge.so` | `app/src/main/cpp/` (CMake) | C++ bridge, **only built when `DOVI_NATIVE_ENABLED=true`** | 4 | 16 KB (`-Wl,-z,max-page-size=16384`) |
| `DV7/libdovi/<abi>/lib/libdovi.a` | committed prebuilt static archive | **Rust** (rustc 1.93.0 strings) — `dovi_tool`'s libdovi C API | 4 | n/a (static) |

Findings:
- **ABI matrix (verified):** every native dependency ships exactly `armeabi-v7a`, `arm64-v8a`, `x86`,
  `x86_64`, matching `splits.abi.include(...)` and the universal APK. A new native library must cover the
  same four; Rust targets: `armv7-linux-androideabi`, `aarch64-linux-android`, `i686-linux-android`,
  `x86_64-linux-android`, API 24.
- **Rust already ships (verified):** `libdovi.a` is Rust, statically linked into `libdovi_bridge.so` when
  Dolby Vision native support is enabled. A second Rust library must therefore be a separate `.so`
  (two Rust staticlibs in one shared object would clash on runtime symbols) — **inferred**.
- **16 KB pages (verified):** `libconscrypt_jni.so` and `libiamf.so` have 4 KB LOAD alignment on arm64;
  everything else is 16 KB-aligned. Google Play requires 16 KB support for apps targeting Android 15+;
  these two would need upstream updates (decision needed, §9).
- **Packaging (verified):** `packaging.jniLibs.useLegacyPackaging = true` (libs extracted at install) and
  `pickFirsts` for `libc++_shared.so` and FFmpeg libs (mpv vs FFmpeg AARs overlap).
- **Loading (verified):** `nuvio_engine` loads in `NativeBridge`'s static initialiser on first engine use;
  `dovi_bridge` loads lazily in `core/player/DoviBridge.kt` with failure → `false`; FFmpeg and Media3 JNI
  loaders catch `UnsatisfiedLinkError`. Nothing is loaded in `Application.onCreate`. **Risk (inferred):**
  `TorrentService` catches `Exception`, but `UnsatisfiedLinkError` is an `Error`, so a missing/unsupported
  engine library would escape (§9).
- **Stray artefacts (verified):** `org/libtorrent4j/swig/byte_vector.class` is tracked but referenced by
  no build file; `libmpv-android/` is an empty submodule path (the mpv dependency comes from Maven).

## 3. `NuvioEngine` source and torrent-service relationship

- **Origin (verified):** `com.nuvio.engine.*` comes only from `app/libs/lib-nuvio-engine-android-0.1.6.aar`
  (`implementation(files(...))`, both flavours). Its `classes.jar` is Kotlin (`NuvioEngine`,
  `NuvioEngineConfig`, `internal/NativeBridge` with 15 `external` methods); `NativeBridge` calls
  `System.loadLibrary("nuvio_engine")`.
- **Implementation language (verified): C/C++, not Rust.** `libnuvio_engine.so` contains
  `libtorrent/2.0.12`, Boost.Asio and `openssl-3.5.7` source paths, a C API (`nuvio_engine_*`), `.comment`
  = Android clang 21 / LLD, and zero `rustc`/`.cargo` markers. It NEEDs only `libc`, `libm`, `libdl`.
  The AAR's bundled notices state the engine is GPL-3.0-or-later.
- **Source repository: unknown.** Not in this checkout.
- **Relationship (verified):** `core/torrent/TorrentService.kt` is a Hilt `@Singleton` (not an Android
  `Service`), scope `SupervisorJob() + Dispatchers.IO`, `startMutex`-serialised. `ensureEngine()`
  (re)creates `NuvioEngine` when config changes (state in `noBackupFilesDir/nuvio-engine/state`, payload
  cache in `cacheDir/nuvio-engine`); `startStreamLocked` adds the magnet, prepares the stream and publishes
  `TorrentState.Streaming(localUrl)`, which ExoPlayer consumes. The engine serves HTTP range requests on
  loopback (**inferred** from `127.0.0.1`/`Content-Range` strings; port **unknown**).
  `PlayerRuntimeController.onCleared()` calls `torrentService.shutdown()`, so every player exit tears the
  session down and the next torrent playback pays a cold start (**inferred**).
- **Unrelated name clash (verified):** `androidx.media3.common.NuvioEngineConfig` is part of the Media3 fork.

## 4. Flavour and plugin compatibility map

| Concern | `full` | `playstore` |
|---|---|---|
| applicationId (release / debug) | `com.nuvio.tv` / `com.nuviodebug.com` | `com.nuvio.app` / `com.nuvio.appdebug` |
| `FEATURE_PLUGINS_ENABLED`, in-app updates, in-app trailers, playback keep-alive, custom servers | true | false |
| `FEATURE_EXTERNAL_TRAILERS_ENABLED` | true | true |
| `core/plugin/PluginManager.kt` | real: QuickJS (`NUVIO_JS`) + CloudStream DEX (`EXTERNAL_DEX`) | stub: same public API, empty flows/results, `UnsupportedOperationException` for repo ops |
| Updater (`updater/*`, `UpdateJobService`, ART `.dm` embedding) | yes (GitHub Releases of **NuvioMedia/NuvioTV**) | stubbed |
| Manifest | adds `UPDATE_PACKAGES_WITHOUT_USER_ACTION`, update service/receiver | removes install-packages / foreground-service permissions and `ExternalPlaybackKeepAliveService` |
| Native extras | `libquickjs.so`, IAMF, Conscrypt | — |
| Torrent engine | yes | yes |

All **verified** (`app/build.gradle.kts` flavours, `src/full`, `src/playstore` file lists).

QuickJS runtime (full only, **verified** in `src/full/.../core/plugin/PluginRuntime.kt` and `PluginManager.kt`):
- A fresh QuickJS instance per execution on a 10-thread `plugin-worker` pool (`THREAD_PRIORITY_BACKGROUND`);
  orchestration on `Dispatchers.IO.limitedParallelism(10)`; nothing on the main thread.
- Limits: `PLUGIN_TIMEOUT_MS = 60_000`, `SCRAPER_TIMEOUT_MS = 120_000`, fetch body ≤ 1 MiB, plugin
  download ≤ 5 MiB, `MAX_RESULT_ITEMS = 150` (non-streaming path only), `MAX_CONCURRENT_SCRAPERS = 10`,
  60 ms launch stagger, single-flight dedup per `scraperId:tmdbId:type:season:episode`.
- **No QuickJS memory or stack limit is set** (binding exposes `setMemoryLimit`/`setMaxStackSize`;
  defaults **unknown**).
- Cancellation cancels in-flight OkHttp calls; failures become empty results (isolation by catch-all).
- Host functions: `__native_fetch` (synchronous — a plugin's parallel fetches serialise, **inferred**),
  Jsoup-backed cheerio API, URL parsing, scraper settings/TMDB key, restricted `require()`.
- Output: Gson → `LocalScraperResult` (`domain/model/Plugin.kt`).

Provider flow (**verified**, `data/repository/StreamRepositoryImpl.kt`): addons, server sources and the
plugin job run concurrently and feed a `Channel.UNLIMITED`; every arrival is merged
(`mergePresentedResult` → `mergeStreams`, `LinkedHashMap` keyed by `dedupKey()`) and re-emitted, so the
UI gets growing partial results. `DebridStreamPresentation.apply` runs
`DirectDebridStreamFilter.applyPreferencesWithFacts` (classify → filter → sort → limit) on the merged
debrid list on each arrival. `StreamSearchSessionCache`: 15 min TTL, 12 entries. These semantics
(ordering, partial emission, cancellation, dedup) are what a Rust boundary must not change.

## 5. Build, CI, test and device compatibility

- **Build (verified, run here):** `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*'
  :app:assembleFullDebug` on the unmodified checkout → **BUILD SUCCESSFUL in 15m 56s** (cold cache,
  2 vCPU), 69 tasks. Requires `local.properties` with `sdk.dir` (empty secrets are fine) and the four
  `NUVIO_RELEASE_*` signing variables, because **debug builds use the `release` signing config**
  (`buildTypes.debug.signingConfig = signingConfigs.getByName("release")`) whose default keystore
  `../nuviotv.jks` is not in the repo. CI generates a throwaway keystore for this.
- **APK sizes (verified):** fullDebug arm64 75.8 MB, armeabi-v7a 73.9 MB, x86 76.5 MB, x86_64 77.4 MB,
  universal 156.6 MB.
- **Dolby Vision bridge is not built in CI or by default (verified):** CMake is only configured when
  `DOVI_NATIVE_ENABLED` is truthy in `local.properties`/`local.dev.properties`.
- **CI (verified):** `pr-full-debug-build.yml` (PRs to `dev`) runs only the three `updater` unit-test
  files plus `assembleFullDebug`; `android-release.yml` (manual) does the same for release and the 21
  Python release-script tests. No lint, no full unit-test run, no instrumented tests, no benchmarks.
  Direct pushes to `dev` run nothing.
- **Secrets (verified, names only):** `LOCAL_PROPERTIES_BASE64`, `LOCAL_DEV_PROPERTIES_BASE64`,
  `NUVIO_RELEASE_KEYSTORE_BASE64`, `NUVIO_RELEASE_KEY_ALIAS`, `NUVIO_RELEASE_KEY_PASSWORD`,
  `NUVIO_RELEASE_STORE_PASSWORD`. **Hard-coded fallback signing passwords** exist in
  `app/build.gradle.kts` (`"815787"`).
- **Tests (verified):** 388 unit-test files (379 with `@Test`; JUnit4, MockK, coroutines-test,
  MockWebServer, `unitTests.isReturnDefaultValues = true`), 19 instrumented test files, 21 Python tests.
- **Device compatibility (verified config / inferred behaviour):** minSdk 24 with leanback launcher,
  touchscreen not required, ABI splits for all four ABIs. x86/x86_64 serve emulators and some TV boxes.
  No hardware verification was possible here.

## 6. UniFFI vs JNI recommendation

| Criterion | UniFFI | Hand-written JNI (`jni` crate) |
|---|---|---|
| API shape needed | Generates Kotlin bindings for rich types | Fine for a narrow surface of primitive/String arrays |
| Runtime deps | JNA (+ `libjnidispatch.so` ×4 ABIs, ~1 MB) and generated code; JNA calls are slower than direct JNI | None beyond the `.so`; direct `RegisterNatives`-free symbol binding like `NuvioEngine`/`DoviBridge` |
| R8/ProGuard | JNA needs keep rules for reflection | One keep rule for the native class |
| Async/cancellation | Async support exists but needs a Rust executor; brief forbids Tokio by default | Synchronous calls on caller's dispatcher; cooperative cancellation between batches |
| Errors | Mapped to Kotlin exceptions automatically | Explicit: `IllegalArgumentException`/`IllegalStateException`, panics caught |
| Build/CI | `uniffi-bindgen` step + version lock between generator and runtime | cargo-ndk only |
| Consistency with repo | No existing UniFFI use | Matches existing JNI pattern (engine, DoVi, Media3 fork) |

**Recommendation: JNI with the `jni` crate (0.21.1)**, a single library `libnuvio_core.so`, an explicit
ABI version handshake, and flat typed batch layouts (no JSON across the boundary). Revisit UniFFI only
if the surface grows to many rich types where generated bindings save real effort.

## 7. Ranked Rust candidates

Most of the app is network-bound; heavy home/continue-watching work already runs on
`Dispatchers.Default`. No profiling data exists. Ranking (benefit / cost / risk):

1. **Debrid stream release-name classification** — `core/debrid/DirectDebridStreamFilter.kt`
   (`streamFacts`): ~20 regexes and repeated lowercase/joins per stream, re-run on the whole merged list on
   every provider arrival, pure, 14 existing tests. Benefit low–medium (tens to hundreds of streams per
   title, weak TV CPUs), cost medium, risk medium (regex semantics). **Chosen for Phase 1** as the
   "provider-result normalisation" prototype because it is pure, bounded and testable, and the batch
   boundary is clean.
2. **Subtitle auto-sync retimer** — `ui/screens/player/autosync/AutoSyncTimelineRetime.kt` (2365 lines,
   numeric DP over ±180 s): the only genuinely numeric workload, 33 tests. Benefit potentially high,
   cost high, risk medium-high (tuned heuristics; exact float parity). Measure with its built-in
   `timingObserver` on a device first.
3. **Stream badge matching** (`core/streams/StreamBadgeRules.kt`): user-supplied regexes may use
   look-around/backreferences unsupported by Rust's `regex` — high risk, low benefit.
4. **Subtitle charset detection / cue parsing**: low benefit (~100 KB files), medium risk.
5. **Meta/catalog mapping**: network-bound; fix per-episode regex construction in Kotlin instead.
6. **Library sync reducer**: negligible benefit; useful only as a toolchain smoke test.
7. **Search suggestion ranking**: not CPU-bound.
8. **Watch-progress merges**: entangled with DataStore/sync; high cost/risk.

**Update (Milestone 4).** The library grid query (`LibraryViewModel.withVisibleItems`) was missing
from this list. It turned out to be the largest CPU cost measured so far: 6–58 ms per filter or sort
press for libraries of 2 000–10 000 entries, on the main thread. It is resolved in Kotlin by
`LibraryGridIndex`. Rust was evaluated by ceiling analysis and rejected; see `PERFORMANCE.md` §Search and
filter.

## 8. Performance baseline and benchmark plan

- **Existing infrastructure (verified):** `baselineprofile/` `NuvioStartupBenchmark` (cold start,
  `timeToInitialDisplayMs`, 5 iterations) and `NuvioFrameMetricsBenchmark` (5 journeys × with/without
  profile, `FrameTimingMetric`); `scripts/perf_baseline.sh` orchestrates them. **No baseline numbers are
  committed**; all need a connected Android TV device or emulator.
- **Baseline captured here:** build time and APK sizes (§5); host-JVM microbenchmarks of the classifier
  in `docs/nuvio-next/PERFORMANCE.md`.
- **Plan:** (1) on a reference TV device, run `scripts/perf_baseline.sh benchmark` before and after any
  Rust feature is enabled; (2) add an on-device measurement of `applyPreferencesWithFacts` for realistic
  stream counts (log timing around `DebridStreamPresentation.apply`); (3) enable a Rust path by default
  only if it improves device latency without regressing startup (library load is lazy and off the main
  thread) or APK size materially.

## 9. Risks, unknowns and decisions needed

1. **Updater points at upstream (verified):** `GITHUB_OWNER = "NuvioMedia"`, `GITHUB_REPO = "NuvioTV"`.
   A Nuvio Next full build would offer to "update" itself to upstream releases. *Decision needed.*
2. **Signing:** hard-coded fallback passwords and debug builds signed with the release config. *Decision
   needed* on removing fallbacks (would break any workflow relying on them).
3. **16 KB page size:** `libconscrypt_jni.so`, `libiamf.so` are 4 KB-aligned. Needs dependency updates.
4. **`UnsatisfiedLinkError` from the torrent engine** was not caught by `catch (Exception)` paths. **Fixed:** `TorrentService.ensureEngine` now converts it to `IllegalStateException`, covered by `TorrentServiceNativeLoadFailureTest`.
5. **No QuickJS memory/stack limits**; synchronous `fetch` serialises plugin I/O (inferred).
6. **Engine shutdown on every player exit** (cold restarts) — behaviour choice, not changed.
7. **Unknown:** NuvioEngine source repository/build reproducibility; libdovi build provenance; QuickJS
   fork source; HTTP port of the engine's loopback server.
8. **Upstream sync (verified, corrected):** the fork's first commit (`546c9d1`) sits directly on upstream
   `NuvioMedia/NuvioTV` `dev` (`11689894f`, merge of PR #4598), so upstream changes can be merged normally.
   An `upstream` remote is not configured in this workspace.
9. **CI coverage is minimal** (three test files); broader unit-test runs are a cheap win.
10. **Brief vs. instructions:** the brief asks for a read-only audit and no direct pushes to `dev`; the
    owner's task asked for implementation. Work was delivered on `feature/nuvio-next-rust-core` with a PR
    into `dev` (the platform also blocks direct pushes to the default branch).
11. **Pre-existing test failures (verified, identical on upstream `dev`):** `TraktAuthServiceTest` needs
    Trakt client credentials in `local.properties` and now skips without them.
    `SearchViewModelSuggestionsTest` › "clearing the field retires the suggestions and the submitted
    query" contradicts `SearchViewModel.onQueryChanged`'s recomposition-artifact heuristic. That
    heuristic keeps suggestions when the query drops from the last non-empty value to empty. Which
    behaviour is right on TV hardware is a product decision; it is left unchanged. The wall-clock
    assertion in `TrackingPlaybackScenariosTest` scenario 5 (< 20 ms) can flake under CPU load.
    `TraktProgressServiceFailureTest` (real-time waits with 15 s timeouts) is intermittent on
    unmodified `dev` (`b8b0e66`): 2 of 6 isolated runs timed out, once in each test method.
    `ParallelRangeChunkLeaseTest` › "releasing a buffer nobody reads…" fails occasionally under full-suite
    load and passed 3 of 3 isolated runs.

## 10. Minimal Phase 1 plan (implemented on `feature/nuvio-next-rust-core`)

1. `rust/` Cargo workspace: `nuvio-core` (pure logic, no deps) and `nuvio-core-jni` (`cdylib`
   `libnuvio_core.so`, `jni` 0.21.1), pinned toolchain `rust/rust-toolchain.toml`, 16 KB page alignment
   in `rust/.cargo/config.toml`.
2. `app/build.gradle.kts`: per-variant `CargoNdkBuildTask` (cargo-ndk, 4 ABIs, API 24) added as a
   generated `jniLibs` source; `buildRustCoreHost` for JVM unit tests; `-Pnuvio.rustCore=false` opt-out;
   `RUST_RELEASE_FACTS_ENABLED` BuildConfig flag (default off).
3. Kotlin: `core/nativecore/NuvioCore.kt` (lazy load, ABI handshake, never throws),
   `NuvioCoreNative.kt` (JNI surface), `core/debrid/ReleaseFactsClassifier.kt` (Kotlin reference moved
   unchanged from `DirectDebridStreamFilter`), `NativeReleaseFacts.kt` (batch codec + enum-table check).
4. Tests: shared fixtures `rust/nuvio-core/tests/fixtures/release_facts.json` checked by Kotlin, Rust
   (`cargo test`) and the JNI library; seeded differential fuzz (10 000 streams); malformed-input tests;
   opt-in benchmark.
5. CI: Rust fmt/clippy/test and cargo-ndk in PR and release workflows; debrid tests added to the PR run.

## 11. Commands run and results

| Command | Result |
|---|---|
| `unzip -l app/libs/*.aar`, `readelf -d/-p .comment/-lW`, `strings`, `javap` on engine AAR and APK libs | Inventory in §2–§3 |
| `./gradlew :app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*' :app:assembleFullDebug` (original checkout) | BUILD SUCCESSFUL, 15m 56s |
| `PYTHONPATH=scripts python3 -m unittest discover -s scripts/tests` | 21 tests OK |

Results for the Phase 1 implementation are in `docs/nuvio-next/RUST_INTEGRATION.md` and
`docs/nuvio-next/PERFORMANCE.md`.
