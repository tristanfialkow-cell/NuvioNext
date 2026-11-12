# Nuvio Next — Project Brief

> This file is the working source of truth for AI coding agents contributing to Nuvio Next. Read it before inspecting or changing code. When repository evidence conflicts with an assumption here, report the discrepancy rather than silently following either one.

## 1. Project goal

Nuvio Next is a long-term remake/evolution of [NuvioTV](https://github.com/NuvioMedia/NuvioTV), maintained in this fork:

- **Fork:** https://github.com/TristanFialko/NuvioNext
- **Upstream:** https://github.com/NuvioMedia/NuvioTV
- **Working branch:** `dev`

The aim is to evolve the application deliberately, with a maintainable architecture, strong Android TV usability, reliable playback, and measurable performance improvements. This is not a licence to rewrite working subsystems simply to introduce new technology. Preserve existing functionality and compatibility unless a change is explicitly planned, justified, and tested.

## 2. Agreed architecture: Kotlin + Rust hybrid

The project will use Kotlin for Android/platform responsibilities and Rust for selected portable, CPU-intensive core logic.

### Kotlin remains responsible for

- Android application lifecycle, services, permissions, and platform APIs.
- Jetpack Compose UI and Android TV navigation/focus handling.
- Media3 / ExoPlayer, playback lifecycle, renderers, decoders, and subtitles.
- Android-specific storage and integrations.
- Existing QuickJS plugin runtime and its Android-facing bindings, at least during the initial migration.
- Wiring, orchestration, and presentation at the Android application layer.

### Rust is intended for carefully selected core work

Potential initial candidates, subject to profiling and audit:

- Metadata parsing, validation, and normalisation.
- Search, filtering, ranking, and indexing operations.
- Provider result normalisation and aggregation.
- Other demonstrably CPU-bound, portable operations where a stable boundary exists.

### Kotlin/Rust boundary

- Keep the boundary narrow, explicit, typed, and versionable.
- Avoid sending huge JSON strings or repeatedly copying large object graphs across the FFI boundary.
- Define clear ownership, threading, cancellation, error, and lifecycle rules.
- Evaluate UniFFI and JNI against actual project requirements before selecting one. Do not add both without a concrete reason.
- Rust Android builds must cover only ABIs that the app and target devices actually support. Confirm the existing native ABI matrix before changing packaging.
- Use a Cargo workspace and appropriate Android NDK tooling (for example, cargo-ndk) only after the integration design is documented.
- Use Tokio only where async runtime functionality is genuinely needed; do not introduce an async runtime by default.
- Use Serde where it provides a clear benefit.

## 3. Do not rewrite these systems in the first Rust milestone

Keep these Kotlin/Android or existing native systems intact initially:

- Media3 / ExoPlayer and the playback pipeline.
- Android lifecycle, services, Compose UI, and TV input/focus handling.
- The QuickJS runtime, plugin API, and existing plugin compatibility.
- Existing FFmpeg / CMake / C++ integration.
- Dolby Vision native bridge.
- Existing torrent engine integration until its implementation, language, packaging, ABI support, and lifecycle are verified.

Do not assume that the existing `com.nuvio.engine` torrent dependency is Rust. Establish its origin and implementation from repository evidence first.

## 4. Repository facts previously observed — verify against the current checkout

The following were observed in a prior remote source inspection. They are useful starting points, not a substitute for inspecting the current local checkout.

- The project uses Kotlin, Jetpack Compose, TV Material 3, Hilt, and Media3.
- The app has `full` and `playstore` product flavours. The full flavour contains the real plugin implementation; the Play Store flavour has a plugin stub.
- QuickJS is used for plugin execution.
- Plugin/provider handling already has concurrency limits, response-size limits, and timeouts. Preserve these protections.
- Playback has a substantial dependency graph and integrations beyond simply opening a media URL.
- Native integrations include FFmpeg and a Dolby Vision bridge.
- DataStore is widely used, including profile-aware preferences and progress persistence. Media3 also has its own playback cache/database infrastructure.
- Macrobenchmark infrastructure already exists for startup and key UI/player flows. Reuse it rather than creating a competing benchmark system.

Useful paths previously observed (confirm each exists before relying on it):

- `app/src/main/java/com/nuvio/tv/data/repository/StreamRepositoryImpl.kt`
- `app/src/main/java/com/nuvio/tv/domain/repository/StreamRepository.kt`
- `app/src/full/java/com/nuvio/tv/core/plugin/PluginManager.kt`
- `app/src/full/java/com/nuvio/tv/core/plugin/PluginRuntime.kt`
- `app/src/playstore/java/com/nuvio/tv/core/plugin/PluginManager.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerViewModel.kt`
- `app/src/main/java/com/nuvio/tv/ui/screens/player/PlayerMediaSourceFactory.kt`
- `app/src/main/java/com/nuvio/tv/core/player/TrailerPlayerPool.kt`
- `app/src/main/java/com/nuvio/tv/core/player/DoviBridge.kt`
- `app/src/main/cpp/CMakeLists.txt`
- `app/src/main/java/com/nuvio/tv/core/torrent/TorrentService.kt`
- `app/src/main/java/com/nuvio/tv/data/local/WatchProgressPreferences.kt`
- `baselineprofile/src/main/java/com/nuvio/tv/baselineprofile/NuvioStartupBenchmark.kt`
- `baselineprofile/src/main/java/com/nuvio/tv/baselineprofile/NuvioFrameMetricsBenchmark.kt`
- `scripts/perf_baseline.sh`

The README previously documented this build command; verify it still applies:

```bash
./gradlew :app:assembleFullDebug
```

## 5. Migration order

Treat this as a staged decision sequence, not a promise that every candidate must move to Rust.

1. **Baseline and audit:** understand the current build, native dependencies, ABIs, application behaviour, tests, and performance.
2. **Minimal Rust integration proof:** add the smallest possible Rust library/bridge diagnostic, prove Android packaging and lifecycle, and test supported ABIs.
3. **Metadata core:** prototype parsing/normalisation with identical Kotlin and Rust fixtures; compare correctness, allocations, latency, and memory.
4. **Search/filter core:** migrate only if benchmarks and maintainability justify it.
5. **Provider result processing:** preserve provider/plugin contracts, ordering, partial results, cancellation, timeouts, and error semantics.
6. **Library indexing:** evaluate after understanding existing persistence and caching.
7. **Provider orchestration:** consider only after plugin compatibility and cancellation semantics are fully mapped.
8. **Further storage or playback changes:** out of scope unless profiling or a concrete requirement proves they are necessary.

Do not add SQLite, another database, or a second cache layer merely because Rust is being introduced. Existing DataStore and Media3 persistence must be understood first.

### Status

Evidence for every decision is in `docs/nuvio-next/PERFORMANCE.md`; the audit is `docs/nuvio-next/AUDIT.md`.

| Step | Status | Outcome |
|---|---|---|
| 1. Baseline and audit | Done (PR #1) | Audit complete; `com.nuvio.engine` is prebuilt C++ (libtorrent), not Rust. |
| 2. Rust integration proof | Done (PR #1) | `rust/` workspace, JNI bridge `libnuvio_core.so` for 4 ABIs, 16 KB aligned; ABI handshake and Kotlin fallback. |
| 5 (part). Release-name classification | Rust, debug builds only | 4.5–5.5× faster on the host; release builds stay on Kotlin until measured on devices. |
| 3. Metadata core | Decided: Kotlin (PR #2) | Moshi decoding dominates; fixed a regex hot spot instead. |
| 4. Search/filter core | Decided: Kotlin (Milestone 4) | Library grid query 19–300× faster via `LibraryGridIndex`; the Rust saving ceiling is ≤ 0.13 ms per query. |
| 6. Library indexing | Done in Kotlin (Milestone 4) | `LibraryGridIndex` holds precomputed keys and cached sort orders per library snapshot, in memory only. |
| 5. Provider result processing | Next | — |

The owner's long-term direction (2026-11-12) is for Rust to become the primary engine for
performance-critical core logic: data processing, search, filtering, indexing, provider processing
and aggregation. Each move still has to be justified by benchmarks that include JNI costs.
Kotlin keeps UI, lifecycle, platform APIs, Media3 and device integrations. New Rust paths stay
behind flags in release builds until correctness, device performance and compatibility are verified.

## 6. First milestone: read-only technical audit

Before implementing the Rust bridge or changing application architecture, complete a repository audit.

### Build and project structure

- Gradle settings, root and module build files, product flavours, build types, version catalog, plugin versions, and CI workflows.
- Existing build/test commands and any signing or secret dependencies.
- Whether the fork currently builds and what baseline is available. Do not claim a build passed unless it was actually run.

### Native code and ABI compatibility

- All `.so` files, AARs, JNI declarations, CMake projects, external native dependencies, and `System.loadLibrary` calls.
- Where `com.nuvio.engine.NuvioEngine` comes from; determine its implementation language only from evidence.
- Actual packaged ABIs for every native dependency and whether they overlap.
- Rust target triples required for the supported app/device ABI matrix.
- Minimum Android API level and NDK/toolchain constraints.
- Native loading order, lifecycle, thread attachment, ownership, and failure behaviour.

### Providers and plugins

- Full and Play Store flavour differences.
- QuickJS runtime, bindings, plugin API, execution context, threading, timeout, cancellation, result limits, and failure isolation.
- Provider result models and the cleanest Kotlin/Rust boundary.
- Existing concurrency and caching behaviour.

### Playback and Android TV

- Player controller dependencies, Media3 integrations, native decoders, subtitles, caching, torrent handling, and playback state/progress.
- TV focus, remote navigation, input, services, and lifecycle.
- Identify compatibility risks; do not redesign the player during the audit.

### Storage and performance

- DataStore usage, profile scoping, caches, progress persistence, and Media3 database/cache usage.
- Existing unit, instrumented, and macrobenchmark coverage.
- Current performance measurement commands and baseline outputs, if available.
- Candidate CPU-bound operations and measurable performance hypotheses.

### Rust integration options

Compare UniFFI and JNI for this specific codebase. Cover API design, build integration, Kotlin ergonomics, async/threading, cancellation, errors, lifecycle, ABI packaging, debugging, and CI complexity. Recommend one approach and explain trade-offs; do not implement it during the audit.

## 7. Audit deliverables

Produce a concise but evidence-based report containing:

1. Current architecture and module/dependency map.
2. Complete native dependency inventory, including provenance and ABI support.
3. Verified source of `NuvioEngine` and its relationship to the torrent service.
4. Full/Play Store flavour and plugin compatibility map.
5. Build, CI, test, and device compatibility assessment.
6. UniFFI vs JNI recommendation for this repository.
7. Ranked Rust migration candidates, with expected benefit, implementation cost, and risk.
8. Performance baseline and benchmark plan.
9. Concrete risks, unknowns, and questions requiring a decision.
10. A proposed minimal Phase 1 implementation plan with exact files likely to change.

For every important conclusion, cite exact repository paths, symbols, Gradle configuration, build output, or test evidence. Clearly label findings as **verified**, **inferred**, or **unknown**. Do not present assumptions as facts.

## 8. Working rules for AI coding agents

These rules apply to every agent working in this repository:

- Read this file and the relevant source code before acting.
- Work in small, reviewable steps. Prefer targeted patches over broad rewrites.
- Do not modify unrelated files, reformat entire files, or rename/move modules without an explicit reason.
- Do not delete existing features, tests, compatibility layers, or configuration simply to make a change easier.
- Do not add dependencies until their purpose, alternatives, maintenance status, and size/build impact are justified.
- Preserve public APIs and plugin/provider behaviour unless a migration plan explicitly covers compatibility.
- Do not weaken tests, remove assertions, or skip failing checks to obtain a green build.
- Do not fabricate test, benchmark, build, or device results. State exactly what was and was not run.
- For each implementation task, report: summary, exact files changed, rationale, commands run, test results, remaining risks, and any manual verification needed.
- Keep patches limited to the requested task. Do not include generated files or build artefacts unless required.
- If the task is an audit, it is **read-only**: do not create, edit, move, or delete repository files.
- If a task's requirements conflict with this brief or the existing architecture, stop and explain the conflict before making a risky change.

## 9. Git workflow and upstream

- `origin` should point to `https://github.com/TristanFialko/NuvioNext.git`.
- `upstream` should point to `https://github.com/NuvioMedia/NuvioTV.git`.
- Keep development work on feature branches and merge reviewed changes into `dev`.
- Do not push directly to `dev` for substantial implementation work.
- Sync upstream deliberately; inspect upstream changes before merging or rebasing.
- Preserve upstream attribution and the repository's GPL-3.0 licensing obligations. Review the licence and notices before redistribution; do not remove copyright or licence notices.

## 10. Immediate next action

Complete **Milestone 1 — Read-only Technical Audit** against the current checked-out `dev` branch.

Do not begin the Rust implementation yet. Return the audit deliverables in Section 7, distinguish verified facts from assumptions, and make no repository changes.
