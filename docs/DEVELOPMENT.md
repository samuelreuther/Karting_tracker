<!-- generated-by: gsd-doc-writer -->
# Development

This document covers the local development workflow for Karting Tracker: build commands, project
structure, key source packages, patterns for adding new features, the session storage pipeline,
the recording state machine, and the simulated-data tools available for development without a
physical kart session.

---

## Build Commands

The project uses a single Gradle module (`:app`). All commands are run from the repository root.

| Command | Description |
|---|---|
| `./gradlew assembleDebug` | Build a debug APK to `app/build/outputs/apk/debug/` |
| `./gradlew assembleRelease` | Build a release APK (minification is currently disabled) |
| `./gradlew installDebug` | Build a debug APK and install it on a connected device |
| `./gradlew :app:testDebugUnitTest` | Run all JVM unit tests for the debug variant |
| `./gradlew :app:testDebugUnitTest --tests <FQN>` | Run a single test class or method |
| `./gradlew :app:connectedDebugAndroidTest` | Run instrumented tests on a connected device |
| `./gradlew lint` | Run Android Lint |

**JDK requirement:** Set the Gradle JDK to JDK 21 in Android Studio under
_Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK_.
Command-line builds must set `JAVA_HOME` to a JDK 21 installation.
The Gradle wrapper is pinned to **8.14.3**; the Android Gradle Plugin is **8.5.2**.

### Reliability workflow test

To verify lap detection, time-loss computation, and coaching output against three deterministic
simulated sessions, run:

```bash
./gradlew :app:testDebugUnitTest --tests com.kartingtracker.domain.ReliabilityWorkflowTest
```

This test uses `SimulatedSessionGenerator` so it does not require a device or any recorded data.

---

## Module Structure

The project is a single-module Android application. `settings.gradle.kts` declares only one
module:

```
include(":app")
```

All production source code lives under `app/src/main/java/com/kartingtracker/`.
All JVM unit tests live under `app/src/test/java/com/kartingtracker/`.
There are currently no instrumented tests; `app/src/androidTest/` does not exist.

There are no library modules, no product flavors, and no Kotlin Multiplatform targets.

---

## Key Source Packages

```
app/src/main/java/com/kartingtracker/
├── AppContainer.kt          — Manual DI; wires all dependencies at application startup
├── KartingApplication.kt    — Application subclass; creates AppContainer in onCreate()
├── data/                    — Domain models, storage, repository, and simulation
├── domain/                  — Pure Kotlin analysis algorithms (no Android dependencies)
├── sensor/                  — Android sensor capture and calibration
├── service/                 — Foreground service and notification helpers
├── ui/                      — Single ViewModel, all fragments, and UI state types
├── util/                    — Cross-cutting helpers (BatteryOptimizationHelper)
└── worker/                  — WorkManager CoroutineWorker for background analysis
```

### `data/`

Contains every persistent domain type and the central repository.

| File | Purpose |
|---|---|
| `Session.kt` | Top-level session data class; holds lap list, coaching outputs, processing state, and a reference to the binary raw file |
| `Lap.kt` | Per-lap data with its `SensorSample` slice, peak indices, sector boundaries, confidence score, and `LapPhase` |
| `SensorSample.kt` | Single timestamped observation; the 48-byte binary counterpart in the streaming file |
| `TrackProfile.kt` | Aggregated statistical fingerprint built from multiple sessions |
| `TrackLayout.kt` | User-defined spatial metadata: start line, driving direction, corner anchors |
| `Track.kt` | Lightweight track record stored in SharedPreferences |
| `RecordingStateMachine.kt` | Enforces allowed `RecordingState` transitions |
| `RecordingReliabilityModels.kt` | `RecordingState` enum, `RecordingHealth`, and `RecordingIssue` |
| `StreamingSessionWriter.kt` | Real-time binary file writer with a 64 KB buffer and atomic rename on close |
| `SessionStorageManager.kt` | Atomic JSON persistence; manages `sessions/`, `deleted_sessions/`, and `corrupt_sessions/` |
| `SessionRepository.kt` | Central domain facade; owns the state machine, circular buffer, streaming writer, autosave job, and all `StateFlow` state consumed by the UI |
| `TrackManager.kt` | Track CRUD backed by SharedPreferences |
| `TrackProfileManager.kt` | TrackProfile CRUD backed by JSON files |
| `TrackLayoutManager.kt` | TrackLayout CRUD backed by JSON files and image files |
| `SimulatedSessionGenerator.kt` | Generates fully synthetic but realistic karting sessions for development and testing |

### `domain/`

Pure Kotlin; no Android imports. Safe to unit-test on the JVM without Robolectric.

| File | Purpose |
|---|---|
| `LapDetector.kt` | Public entry point; delegates to `LapDetector2` |
| `LapDetector2.kt` | Core detection: resample → prior estimate → `BoundaryGenerator` → `GlobalSegmenter` |
| `BoundaryGenerator.kt` | Scores candidate lap start/finish boundaries |
| `GlobalSegmenter.kt` | Dynamic-programming global optimisation of the boundary path |
| `PeakDetector.kt` | Braking and cornering peak detection from smoothed signal |
| `SectorDetector.kt` | Sector boundary assignment and sector time computation |
| `DrivingCoachAnalyzer.kt` | Segment-level lap comparison; produces `CoachingInsight` list and theoretical best lap time |
| `SessionQualityEvaluator.kt` | Weighted quality score (valid-lap ratio, confidence, variance) |
| `SessionValidityEvaluator.kt` | Detects non-driving recordings |
| `corner/CornerCoachingAnalyzer.kt` | Per-corner performance metrics and `CornerCoachingInsight` objects |

### `sensor/`

Owns the Android `SensorEventListener` lifecycle, gravity calibration, and adaptive rate management.

| File | Purpose |
|---|---|
| `SensorRecorder.kt` | Registers sensor listeners on a `HandlerThread`; drives the pre-start countdown and calibration phases |
| `CalibrationManager.kt` | Averages accelerometer samples over 2 seconds; computes the gravity vector and derives forward/lateral axes |
| `LowPassFilter.kt` | Exponential smoothing (alpha = 0.18) applied to raw sensor events |
| `AdaptiveSensorRateManager.kt` | Downgrades from GAME (50 Hz) through UI (20 Hz) to NORMAL (10 Hz) when the inter-sample drop rate exceeds 30 % |

### `service/`

| File | Purpose |
|---|---|
| `RecordingForegroundService.kt` | Foreground service with `PARTIAL_WAKE_LOCK`, health watchdog, and notification heartbeat |
| `RecorderWatchdogEvaluator.kt` | Stateless watchdog logic (pure; testable) |
| `RecordingNotificationHelper.kt` | Notification channel setup and content builder |

### `ui/`

The app uses a single-activity architecture (`MainActivity`) with the Navigation component.
All fragments share one `SessionViewModel`.

| Location | Purpose |
|---|---|
| `SessionViewModel.kt` | Single ViewModel for all screens; exposes repository `StateFlow`s and handles comparison/analysis computations |
| `AppViewModelFactory.kt` | Factory that provides `SessionRepository` and `SensorRecorder` to `SessionViewModel` |
| `main/MainFragment.kt` | Home and recording screen |
| `sessions/SessionListFragment.kt` | Session library screen |
| `laps/LapsFragment.kt` | Per-lap telemetry screen |
| `comparison/ComparisonFragment.kt` | Lap-vs-lap comparison with map overlay |
| `tracklayout/TrackLayoutFragment.kt` | Track layout editor |

### `worker/`

| File | Purpose |
|---|---|
| `SessionAnalysisWorker.kt` | `CoroutineWorker` that receives `session_id` and `raw_file_path` as input data and calls `SessionRepository.analyzeRawSession()` on `Dispatchers.Default` |

---

## Adding New Features

### Adding a new screen

1. Create a new `Fragment` subclass in an appropriate subdirectory under `ui/`.
2. Add a `<fragment>` entry in `app/src/main/res/navigation/nav_graph.xml` with a unique
   `android:id` and `android:name`.
3. Add `<action>` entries in the source fragment(s) that need to navigate to the new screen.
4. Observe `SessionViewModel` state in the new fragment using `collectLatestIn` / `launchIn`.
   Do not create a second ViewModel; the shared `SessionViewModel` is the single source of truth.

### Connecting a new action to SessionRepository

`SessionViewModel` is the only object that holds a reference to `SessionRepository` in the UI
layer. Add a public method on `SessionViewModel` that launches in `viewModelScope`, then call
it from the fragment. Never call `SessionRepository` directly from a fragment.

```kotlin
// In SessionViewModel.kt
fun myNewAction(param: String) {
    viewModelScope.launch(Dispatchers.IO) {
        sessionRepository.someRepositoryMethod(param)
    }
}
```

### Connecting a new action to SessionStorageManager

`SessionStorageManager` is accessed exclusively through `SessionRepository`. Add the storage
logic to `SessionRepository` and call it from there. `SessionStorageManager` is not exposed
outside the `data` package.

### Adding a new domain algorithm

Place pure analysis code in `domain/`. Algorithms in this package must not import any Android
classes. Write JVM unit tests in `app/src/test/java/com/kartingtracker/domain/` using
`SimulatedSessionGenerator` to produce realistic input data.

### Adding a new `RecordingState` or transition

1. Add the new state constant to the `RecordingState` enum in `RecordingReliabilityModels.kt`.
2. Add the transition rules to the `allowedTransitions` map in `RecordingStateMachine.kt`.
   Each entry maps a source state to the set of legal target states.
3. Update the state machine diagram in `docs/ARCHITECTURE.md`.
4. Drive the new transition from `SessionRepository` using `transitionRecordingState()`.
   Use `stateMachine.forceSet()` only as a last-resort failure path — it bypasses the
   transition guard and should only be used for `FAILED` recovery.

---

## Session Storage Flow

Understanding the full lifecycle of a session from start to final JSON is essential when
modifying recording, finalization, or reprocessing behavior.

### Phase 1 — Real-time streaming

When recording starts, `SessionRepository.startSession()` creates a new
`StreamingSessionWriter` targeting `sessions/session_<id>_raw.tmp`. Every `SensorSample`
arriving via `appendSample()` is:

1. Added to the `CircularBuffer<SensorSample>` (capacity 1,000; oldest entries evicted).
2. Written asynchronously to the `StreamingSessionWriter` which appends to the 64 KB
   in-memory `ByteBuffer`. The buffer is flushed to the `FileChannel` every second by a
   background coroutine.

In parallel, an autosave coroutine runs every 5 seconds. It snapshots the `CircularBuffer`
contents and calls `SessionStorageManager.saveSession()` with `isPartial = true`, producing
`sessions/session_<track>_<epochMs>_partial.json`.

### Phase 2 — Stop and raw persistence

When the user taps Stop, the state machine advances: `RECORDING → STOPPING → SAVING_RAW`.

`StreamingSessionWriter.finalize()`:
1. Flushes the remaining 64 KB in-memory buffer to the `FileChannel`.
2. Closes the channel.
3. Atomically renames `session_<id>_raw.tmp` → `session_<id>_raw.bin`.

`SessionStorageManager.saveSession()` then writes a minimal JSON record (no `samples` array,
`processingState = "raw_saved_processing_pending"`) referencing the `.bin` file path. The
state machine advances to `RAW_SAVED`.

### Phase 3 — Background analysis (WorkManager)

`SessionRepository.scheduleSessionAnalysis()` enqueues a `OneTimeWorkRequest` for
`SessionAnalysisWorker` keyed as `"analyze_session_<id>"` with
`ExistingWorkPolicy.KEEP` (prevents duplicate analysis for the same session).

`SessionAnalysisWorker.doWork()`:
1. Retrieves `session_id` and `raw_file_path` from the input data.
2. Calls `SessionRepository.analyzeRawSession(sessionId, rawFilePath)` on `Dispatchers.Default`.

`SessionRepository.analyzeRawSession()`:
1. Loads the minimal JSON metadata from `SessionStorageManager`.
2. Reads all samples from the `.bin` file via `StreamingSessionWriter.loadSamplesFromBinaryFile()`.
3. Calls `processSessionInternal()` which runs:
   - `LapDetector.detect()` (→ `LapDetector2`)
   - `PeakDetector.findBrakingPeaks()` and `findCorneringPeaks()`
   - `SectorDetector.detectSectors()` and `computeSectorTimes()`
   - `SessionValidityEvaluator.evaluate()`
   - `DrivingCoachAnalyzer.analyzeSession()`
   - `CornerCoachingAnalyzer.analyze()`
4. Saves the fully analyzed session as JSON. The `samples` field is set to `emptyList()` in the
   JSON — samples remain exclusively in the `.bin` file.
5. Updates `_currentSession` and `_latestSession` `StateFlow`s so the UI reacts immediately.

### Binary file format

Each record is exactly 48 bytes written in the `ByteBuffer` native byte order:

| Field | Type | Bytes |
|---|---|---|
| `timestampNs` | Long | 8 |
| `accelX` `accelY` `accelZ` | Float × 3 | 12 |
| `gyroX` `gyroY` `gyroZ` | Float × 3 | 12 |
| `longitudinalAccel` `lateralAccel` `totalAcceleration` `yawRateAbs` | Float × 4 | 16 |

At 50 Hz the file grows at approximately 2.4 KB/s (144 KB/minute).

### Atomic JSON write strategy

Every JSON write performed by `SessionStorageManager.writeAtomically()` follows this sequence:

1. Validate the JSON string (must parse as an object and meet `MIN_PLAUSIBLE_JSON_BYTES = 32`).
2. Write to a `.tmp` sibling file and call `fsync`.
3. Copy the existing target to a `.bak` backup.
4. `Files.move(..., ATOMIC_MOVE)` replaces the target. Falls back to a copy-and-delete if
   `ATOMIC_MOVE` is unavailable.
5. If the target is implausibly small after the move, restore from `.bak`.

---

## RecordingStateMachine

`RecordingStateMachine` (`data/RecordingStateMachine.kt`) owns a `RecordingState` and exposes
two transition methods:

- `transitionTo(next: RecordingState): Boolean` — checks the `allowedTransitions` map and
  returns `false` (without changing state) if the transition is illegal.
- `forceSet(next: RecordingState)` — bypasses the guard. Use only for failure recovery paths.

### Allowed transition table

| From | Allowed targets |
|---|---|
| `IDLE` | `PRESTART_COUNTDOWN`, `RECORDING`, `ABORTED` |
| `PRESTART_COUNTDOWN` | `CALIBRATING`, `ABORTED`, `FAILED` |
| `CALIBRATING` | `RECORDING`, `ABORTED`, `FAILED` |
| `RECORDING` | `STOPPING`, `FAILED` |
| `STOPPING` | `SAVING_RAW`, `FAILED` |
| `SAVING_RAW` | `RAW_SAVED`, `FAILED` |
| `RAW_SAVED` | `PROCESSING`, `FAILED` |
| `PROCESSING` | `COMPLETED`, `FAILED` |
| `COMPLETED` | `IDLE`, `PRESTART_COUNTDOWN`, `RECORDING` |
| `FAILED` | `IDLE`, `PRESTART_COUNTDOWN` |
| `ABORTED` | `IDLE`, `PRESTART_COUNTDOWN` |

### Adding a new state

1. Add the constant to `RecordingState` in `RecordingReliabilityModels.kt`.
2. Add entries to `allowedTransitions` in `RecordingStateMachine.kt` for both the transitions
   _into_ the new state and the transitions _out of_ it.
3. Drive the transition from `SessionRepository.transitionRecordingState()`.
4. Add a unit test in `app/src/test/java/com/kartingtracker/data/RecordingStateMachineTest.kt`
   that verifies the new transitions are allowed and that previously illegal transitions remain
   blocked.
5. Update the state machine diagram in `docs/ARCHITECTURE.md`.

---

## Debug Seeding — Simulated Test Data

`SimulatedSessionGenerator` (`data/SimulatedSessionGenerator.kt`) generates fully synthetic
but structurally realistic karting sessions. It is used for development and testing when no
real recorded session is available.

### How sessions are generated

`generateSeededSession(trackName, seed, durationMinutes)`:

1. Constructs a `Random` seeded with `baseSeed xor trackName.hashCode() xor seed` to ensure
   determinism across builds and platforms.
2. Resolves a `TrackPattern` from the track name. Named tracks (`"loerrach vm kart racing"`)
   use a bundled `BundledLayoutSpec` with real corner geometry. Unknown track names fall back
   to a 5-corner default pattern.
3. For each lap, builds a `LapProfile` with per-lap braking timing variability, cornering load,
   exit acceleration, and an `imperfectLap` flag (19 % probability).
4. For each sample within a lap, generates `totalAcceleration`, `longitudinalAccel`,
   `lateralAccel`, and `yawRateAbs` signals using wave functions, pulse shapes, and calibrated
   noise. Imperfect laps include a disturbance pulse at a randomly chosen corner.
5. Returns a raw `Session` with `samples` populated and `laps = emptyList()`.

Default parameters for the three seeded debug sessions:

| Parameter | Value |
|---|---|
| Seeds | `42`, `1337`, `9001` |
| Duration | 10 minutes |
| Lap count | 23–25 laps |
| Sample count | ~12,000 samples |
| Sample interval | 50 ms (20 Hz) |

### Generating seeded sessions during development

`SessionRepository.generateSeededSessionsForTrack(trackName, seeds, durationMinutes)` runs the
full processing pipeline (`processSessionInternal`) on each generated session before saving it.
The sessions appear immediately in the session library with laps, coaching, and time-loss outputs.
Call this via `SessionViewModel.generateSeededSessionsForSelectedTrack()` — a debug action
exposed in the main screen's overflow menu in debug builds.

```kotlin
// Trigger from a Fragment or test:
viewModel.generateSeededSessionsForSelectedTrack(
    seeds = listOf(42, 1337, 9001),
    durationMinutes = 10
) { count, trackName ->
    // count sessions generated for trackName
}
```

### Using SimulatedSessionGenerator in unit tests

Call `SimulatedSessionGenerator.generateSeededSession()` directly for JVM unit tests. The
generator has no Android dependencies. `ReliabilityWorkflowTest` demonstrates the pattern:

```kotlin
val session = SimulatedSessionGenerator.generateSeededSession(
    trackName = "Test Track",
    seed = 42,
    durationMinutes = 8
)
val result = lapDetector.detect(session.samples, trackProfile = null)
```

---

## Suppressed Debug Tracks

`AppContainer.init` explicitly removes three legacy debug track names on every app start:

```kotlin
listOf("Test Track", "Demo Indoor Track", "sr test").forEach(trackManager::deleteTrack)
```

These names were used in earlier versions when seeded sessions were generated automatically at
startup. Automatic startup seeding was removed to prevent test artifacts leaking into normal
phone usage. The cleanup call ensures any leftover entries from older builds do not appear in
the track selector.

Sessions stored under `Test Track` and `Demo Indoor Track` are excluded from backup exports in
`AppBackupManager` (via `excludedTrackTokens`). Sessions stored under `sr test` are suppressed
in the UI via `TrackManager.isSuppressedDebugTrack()` but are not excluded from backups. If you
seed sessions for development, use the current-track name already selected in the UI via
`generateSeededSessionsForSelectedTrack()` rather than hard-coding one of these suppressed names.

---

## Soft-Delete and Restore

`SessionStorageManager` implements a two-phase delete pattern:

### Soft-delete (`markSessionDeleted`)

1. Locates the session JSON file in `sessions/`.
2. Moves it to `deleted_sessions/` (falls back to copy + delete if `renameTo` fails).
3. Writes an updated JSON with `deletedAt = System.currentTimeMillis()` and `deletionReason`
   set atomically into the moved file.
4. The session is excluded from `loadAllSessions()` results because `loadAllSessions` filters
   out entries where `deletedAt != null`.

### Restore (`restoreSession`)

1. Locates the file in `deleted_sessions/` by session ID.
2. Moves it back to `sessions/`.
3. Writes an updated JSON with `deletedAt = null` and `deletionReason = null`.

### Permanent deletion

`cleanupOldDeletedSessions()` scans `deleted_sessions/`, reads the `deletedAt` timestamp from
each file's JSON, and permanently deletes files older than 7 days
(`permanentDeleteAfterMs = TimeUnit.DAYS.toMillis(7L)`).

`SessionRepository` calls `sessionStorageManager.cleanupOldDeletedSessions()` during session
refresh so stale soft-deleted files are purged without requiring user action.

---

## WorkManager SessionAnalysisWorker

`SessionAnalysisWorker` (`worker/SessionAnalysisWorker.kt`) is a `CoroutineWorker`. It runs on
a `WorkManager`-managed thread, outside the app's foreground lifecycle.

### Scheduling

`SessionRepository.scheduleSessionAnalysis(sessionId, rawFilePath)` builds a
`OneTimeWorkRequest` with:

- `KEY_SESSION_ID`: the session's `Long` ID
- `KEY_RAW_FILE_PATH`: absolute path to the `.bin` file

The work item is enqueued as a unique job:

```
WorkManager.enqueueUniqueWork(
    "analyze_session_$sessionId",
    ExistingWorkPolicy.KEEP,
    workRequest
)
```

`ExistingWorkPolicy.KEEP` means that if the user somehow triggers analysis twice for the same
session (for example by force-stopping and restarting the app), the in-flight job is preserved
and a duplicate is not enqueued.

WorkManager is used rather than a plain coroutine so analysis survives if the app process is
killed between the stop event and the time analysis completes.

### What `doWork()` calls

```
SessionAnalysisWorker.doWork()
    └── sessionRepository.analyzeRawSession(sessionId, rawFilePath)
            ├── StreamingSessionWriter.loadSamplesFromBinaryFile(rawFile)
            ├── LapDetector.detect()          // via processSessionInternal()
            ├── PeakDetector.findBrakingPeaks() / findCorneringPeaks()
            ├── SectorDetector.detectSectors() / computeSectorTimes()
            ├── SessionValidityEvaluator.evaluate()
            ├── DrivingCoachAnalyzer.analyzeSession()
            └── CornerCoachingAnalyzer.analyze()
    └── SessionStorageManager.saveSession()   // final JSON (samples = emptyList())
    └── StateFlow updates → UI reacts
```

`SessionAnalysisWorker` obtains the `SessionRepository` from `KartingApplication.appContainer`
using the application context, which is safe to access from `CoroutineWorker`.

### Return values

- `Result.success()` — analysis completed and the session JSON was saved.
- `Result.failure()` — invalid input data, the raw binary file was missing, or an exception
  was thrown. The worker does not retry; a `FAILED` processing state is written to the session
  JSON for later recovery.

---

## Code Style

The project uses no external linter or formatter configuration beyond the Kotlin compiler. The
following conventions are followed throughout the codebase:

- Kotlin standard library idioms and coroutines throughout; no RxJava.
- `StateFlow` for observable state; no `LiveData`.
- Manual dependency injection via `AppContainer`; no Dagger, Hilt, or Koin.
- All JSON serialization uses Gson with `GsonBuilder().setPrettyPrinting()`.
- `synchronized(lock)` guards all mutable state in `SessionRepository`; no
  `@GuardedBy` annotations but the pattern is consistent.
- Log tags follow the pattern `private const val TAG = "ClassName"` and log messages
  include a `KartingTracker:` prefix for easy filtering.
