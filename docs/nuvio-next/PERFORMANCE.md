# Performance baseline and benchmark plan

Everything below was measured in the development sandbox (2 vCPU x86_64, 8 GiB, Temurin 21 HotSpot).
**No Android device or emulator was available** (no `/dev/kvm`), so none of these numbers describe ART on
TV hardware. They establish a reproducible host baseline and a direction, not a device result.

## Build baseline (original `dev` @ 546c9d1)

| Measure | Value |
|---|---|
| `:app:testFullDebugUnitTest --tests 'com.nuvio.tv.updater.*' :app:assembleFullDebug` | BUILD SUCCESSFUL, 15 m 56 s wall (cold Gradle cache, includes dependency download) |
| fullDebug APK, arm64-v8a / armeabi-v7a / x86 / x86_64 / universal | 75.8 / 73.9 / 76.5 / 77.4 / 156.6 MB |

## Release-name classification: Kotlin vs Rust (host JVM)

`ReleaseFactsBenchmarkTest` (opt-in) times `ReleaseFactsClassifier.classifyKotlin` against
`NativeReleaseFacts.classify` (Rust **including** JNI marshalling and Kotlin result decoding) on identical
batches, 2 s warm-up, median of 31 runs:

```
./gradlew :app:testFullDebugUnitTest --tests '*ReleaseFactsBenchmarkTest' -Pnuvio.bench=true
```

| Pool | Batch | Kotlin µs/batch | Rust µs/batch | Kotlin ns/stream | Rust ns/stream | Speed-up |
|---|---|---|---|---|---|---|
| fixtures | 20 | 826 | 151 | 41 306 | 7 570 | 5.5× |
| fixtures | 100 | 3 713 | 826 | 37 133 | 8 260 | 4.5× |
| fixtures | 500 | 18 530 | 4 122 | 37 060 | 8 245 | 4.5× |
| fixtures | 2 000 | 74 230 | 15 502 | 37 115 | 7 751 | 4.8× |
| mixed | 20 | 4 188 | 483 | 209 408 | 24 172 | 8.7× |
| mixed | 100 | 19 983 | 2 706 | 199 833 | 27 059 | 7.4× |
| mixed | 500 | 99 562 | 13 751 | 199 124 | 27 501 | 7.2× |
| mixed | 2 000 | 403 097 | 55 765 | 201 548 | 27 882 | 7.2× |

"fixtures" = the 59 hand-written release names in the shared fixture file (realistic lengths);
"mixed" adds 2 000 long synthetic streams from the fuzz generator (worst-case text volume).

Interpretation (**inferred**): classification runs over the whole merged debrid list on every provider
arrival (`StreamRepositoryImpl.mergePresentedResult` → `DebridStreamPresentation.apply`). At ~37 µs per
realistic stream on a desktop-class core, a 200-stream title costs ~7 ms per re-presentation in Kotlin
versus ~1.6 ms in Rust; TV SoCs are typically several times slower and the work repeats per arrival.
Whether this is user-visible on devices is **unknown** until measured there.

## Rollout gate

- Debug builds use the Rust path by default (`BuildConfig.RUST_RELEASE_FACTS_ENABLED`), so the entire
  unit-test suite and device dogfooding exercise it; release and benchmark builds keep Kotlin unless built
  with `-Pnuvio.rustReleaseFacts=true`.
- Enable in release when, on a reference TV device: (1) a benchmark build with the flag shows lower
  stream-screen latency for a debrid-heavy title; (2) `scripts/perf_baseline.sh benchmark` startup and
  frame metrics are not worse (the library loads lazily on a background dispatcher, never at startup);
  (3) no `Rust core unavailable` warnings appear in logcat on supported ABIs.

## Metadata core evaluation (Milestone 3)

Brief step 3 asks whether metadata parsing/normalisation should move to Rust. `MetaMappingBenchmarkTest`
(opt-in, `-Pnuvio.bench=true`) times Moshi decoding of a Cinemeta-shaped series response (configured as in
`NetworkModule.provideMoshi`) separately from `MetaDto.toDomain()`, median of 41 runs after warm-up:

| Episodes | JSON | Decode (Moshi) | Map, before | Map, after fix | Map without runtime strings |
|---|---|---|---|---|---|
| 10 | 8 KB | 38 µs | 8.8 µs | 5.0 µs | 1.8 µs |
| 60 | 33 KB | 144 µs | 47 µs | 24 µs | 5.4 µs |
| 250 | 128 KB | 563 µs | 195 µs | 101 µs | 19 µs |
| 1 100 | 557 KB | 2 430 µs | 864 µs | 437 µs | 83 µs |

Findings:
- **About 90 % of mapping time was episode-runtime parsing**, which compiled two regexes per episode
  (`parseEpisodeRuntimeMinutes` in `data/mapper/MetaMapper.kt`). Compiling them once halves mapping time
  with identical results (pinned by `EpisodeRuntimeParsingTest`).
- Decoding is 75–85 % of the remaining cost and is Moshi codegen over a network response. Moving it to Rust
  would mean passing the whole JSON body across JNI and rebuilding the same Kotlin object graph from the
  result. The brief rules out large JSON transfers across the boundary, and the rebuild would cost about as
  much as what is left to save.
- Meta requests run on `Dispatchers.IO` behind a network round trip of tens to hundreds of milliseconds.
  Even the 1 100-episode case spends under 3 ms in CPU on this host.

**Decision: no Rust metadata core.** Keep metadata in Kotlin with the regex fix. Revisit only if
device profiling shows meta decoding on a critical path, for example detail-page open time on a TV.

## Search and filter (Milestone 4)

Profiled with `SearchFilterBenchmarkTest` (opt-in, `-Pnuvio.bench=true`). Library fixtures are
synthetic Trakt/MDBList-shaped libraries with article-prefixed and non-ASCII titles, mixed release
formats, 0–4 genres and 1–3 list memberships. All numbers are medians on this host.

**Library grid query** (`LibraryViewModel.withVisibleItems`): runs on the main thread inside
`_uiState.update` on every type, genre, year, watched or sort change.
- **Before:** the sort comparators re-ran a regex (year extraction, leading-article stripping) on
  every comparison, O(n log n) regex runs per press.
- **After:** `LibraryGridIndex` derives every key once per library snapshot, interns strings, and caches
  one stable total order per sort option. A query is then a linear pass. A stable sort of a subset
  equals the subset taken in the order of the stable sort of the whole list.

| Entries | Scenario | Before (µs) | Index query (µs) | Speed-up | One-off build (µs) | First query per sort (µs) |
|---|---|---|---|---|---|---|
| 500 | title A→Z | 1 798 | 16.6 | 108× | 295 | 94 |
| 2 000 | default (local) | 837 | 44.7 | 19× | 1 306 | 52 |
| 2 000 | released, newest | 5 971 | 45.5 | 131× | 1 328 | 826 |
| 2 000 | title A→Z | 9 123 | 45.8 | 199× | 1 346 | 689 |
| 5 000 | title A→Z | 26 701 | 100.9 | 265× | 3 352 | 2 113 |
| 10 000 | default (provider list) | 3 132 | 126.5 | 25× | 6 779 | 3 280 |
| 10 000 | released, newest | 38 772 | 192.2 | 202× | 6 677 | 5 574 |
| 10 000 | title A→Z | 57 854 | 192.1 | 301× | 6 634 | 4 950 |
| 10 000 | series, unwatched, released | 14 689 | 141.2 | 104× | 6 784 | 5 539 |

- **Build cost:** paid once when the library snapshot changes (sync or edit). Changes to watched IDs
  and filters reuse the index.
- **Correctness:** `LibraryGridIndexParityTest` checks 2 400 randomized queries and edge cases against
  the reference `LibraryQueryEngine`. Edge cases include duplicate IDs, blank names and types, and
  Unicode case pairs (`ß`, `İ`, `ǅ`, Deseret). Results are identical.

**Rust evaluation (ceiling analysis).** Phase timings of a steady-state query:

| Entries | Scenario | Resolve (µs) | Kernel (µs) | Assemble (µs) |
|---|---|---|---|---|
| 2 000 | title A→Z | 0.0 | 26.3 | 19.7 |
| 10 000 | default | 0.0 | 124.5 | 64.1 |
| 10 000 | title A→Z | 0.0 | 130.5 | 64.1 |
| 10 000 | series, unwatched | 13.1 | 94.8 | 30.2 |

Only the kernel (an integer filter, facet and order pass) could move to Rust. Resolve and assemble
create Kotlin objects either way. The kernel's time is therefore an upper bound on any Rust saving:
≤ 0.13 ms per query at 10 000 entries on this host, before JNI costs. That is below 1 % of a 60 fps
frame. The index build cannot move either. It must keep Android's own `Regex` semantics: `\b` is
Unicode-aware on Android (ICU) but ASCII-only on JDK 19+, which already makes host and device differ.
It must also keep the platform's `String.CASE_INSENSITIVE_ORDER`.

**Decision: library index in Kotlin, no Rust executor.** The win came from the data structure, not
the language. A Rust executor would add a native lifecycle for ≤ 0.13 ms.

**Search suggestions** (`SearchSuggestions.ranked`, main thread, per keystroke): the query's word
split now runs once per keystroke instead of once per title.

| Titles | Query | Before (µs) | After (µs) |
|---|---|---|---|
| 400 | single word | 52.4 | 19.0 |
| 400 | multi-word | 270.7 | 171.7 |
| 1 000 | multi-word | 690.6 | 461.1 |

A Rust version would need every candidate title marshalled across JNI on every keystroke. It would
also need Kotlin's `lowercase()` and `\p{L}\p{N}` splitting reproduced exactly. **Decision: Kotlin.**

## Device benchmark plan

1. Build `benchmark` twice (with and without `-Pnuvio.rustReleaseFacts=true`) and run
   `scripts/perf_baseline.sh benchmark` on the same device; compare `timeToInitialDisplayMs` and the five
   frame-timing journeys (existing macrobenchmarks — no new framework).
2. Add on-device timing (`SystemClock.elapsedRealtimeNanos`) around `DebridStreamPresentation.apply` for a
   fixed set of titles with debrid enabled, logged per arrival; record median and p95.
3. Next candidate to measure, not port: `AutoSyncTimelineRetimer` via its existing `timingObserver`.
