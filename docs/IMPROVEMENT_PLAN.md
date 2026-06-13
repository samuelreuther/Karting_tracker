# Karting Tracker — Improvement Plan

> Created 2026-06-13. Scope: **personal/club use**, prioritize **data-integrity bugs first**.
> Data model: **binary `.bin` file is the source of truth for samples; the in-memory
> `CircularBuffer` is a live-preview window only** (Design A).

## Sample flow (confirmed)

```
onSensorChanged -> SessionRepository.appendSample -+-> CircularBuffer(1000)   (live UI + JSON snapshots)
                                                   +-> StreamingSessionWriter -> session_<id>_raw.tmp --(finalize)--> session_<id>_raw.bin
stop -> finalize binary -> save minimal JSON (no samples) -> WorkManager -> analyzeRawSession reads .bin -> full processed session JSON
```

The `.bin` already holds every sample; JSON never stores samples for normal recordings.
Design A is correct: keep the buffer small, treat `.bin` as truth, and make the `.bin`
path survive crashes and reinstalls.

## P0 — Critical data-integrity (done in this branch)

### P0-1 Out-of-order binary writes
`appendSample` launched one coroutine per sample (~50/sec, unordered) -> sample order in
`.bin` was nondeterministic, breaking monotonic-time assumptions. Fixed with a single-consumer
`Channel<SensorSample>` drained by one writer coroutine. Channel closed and drain joined before
`finalize()`.

### P0-2 Crash recovery of orphaned raw files
A process death mid-recording left `session_<id>_raw.tmp` with samples but nothing recovered it
and no JSON existed -> total loss of in-progress run. Fixed by writing a sidecar
`session_<id>_raw.meta.json` at session start and recovering orphans on startup: promote
`*_raw.tmp` -> `*_raw.bin`, rebuild a PENDING session from the sidecar, re-schedule
analysis. Sidecar deleted once analysis is FINAL.

### P0-3 Absolute rawFilePath breaks on reinstall/restore
`rawFilePath` was persisted absolute -> invalid after reinstall, `analyzeRawSession` failed
silently. Fixed: resolve `session_<id>_raw.bin` against the current `sessionDirectory`, using
the stored path only as a fallback hint.

## P1 — High (next)
- Lap-detection accuracy validation with ground-truth fixtures.
- Split god objects (`SessionRepository` 1249 lines, `SessionViewModel` 1013 lines).
- Add CI (`.github/workflows`) running unit tests + lint.
- Expand tests on repository/recorder/writer ordering & recovery.
- Backups exclude raw `.bin`: include `*_raw.bin` + sidecars in `AppBackupManager`.

## P2 — Medium
- Gate the 1-second `uiState` ticker to active recording only.
- Move `SimulatedSessionGenerator` out of `main` into test/debug source set.
- `String.format` with explicit `Locale`.
- Adaptive launcher icon + density mipmaps.
- Gradle version catalog (`libs.versions.toml`).
- Clarify/collapse `LapDetector` -> `LapDetector2` wrapper.
- WorkManager analysis `Constraints` (avoid critically low battery).

## P3 — Release readiness (deferred; not needed for club use)
- Release signing, `isMinifyEnabled = true` + verified ProGuard.
- `targetSdk` bump if published.
- `allowBackup` data-extraction/backup rules.
