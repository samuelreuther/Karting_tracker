<!-- generated-by: gsd-doc-writer -->
# Architecture

Karting Tracker is an Android application that records indoor karting telemetry using only the device's built-in accelerometer and gyroscope — no GPS. Raw sensor data is captured at up to 50 Hz, written to a binary file in real time, and then analysed off the critical path by a WorkManager background job to detect laps, compute sector times, evaluate session quality, and generate per-corner coaching insights.

---

## System Overview

The application follows a unidirectional data flow: sensors feed a foreground service, which drives a repository layer, which dispatches analysis work to a background worker, which writes results back through the same repository to Kotlin `StateFlow` streams consumed by the UI. There is no network layer and no remote database; everything is stored in the app's private file space and in SharedPreferences.

The primary architectural concern is reliability: recording must survive screen-off, battery optimisation, and process restarts. This is achieved through a combination of a `PARTIAL_WAKE_LOCK`, a foreground service, a health watchdog, periodic in-memory autosave, real-time streaming to a binary file, and an atomic rename strategy for all JSON persistence.

---

## Component Diagram

```
┌─────────────────────────────────────────────────────────┐
│  UI Layer                                               │
│  MainActivity / Fragments (main, sessions, laps,        │
│  comparison, tracklayout)                               │
│         │ observes StateFlow                            │
│         ▼                                               │
│  SessionViewModel  ◄──────── SessionRepository ◄───┐   │
└──────────────────────────────────────┬──────────────┼───┘
                                       │              │
           ┌───────────────────────────┘              │
           │ starts / binds                           │
           ▼                                          │
  RecordingForegroundService                          │
  (wake lock, watchdog, notification heartbeat)       │
           │ controls                                 │
           ▼                                          │
  SensorRecorder  ──────────────────────────────────► │
  (HandlerThread, LowPassFilter,                      │ appendSample /
   CalibrationManager,                                │ startSession /
   AdaptiveSensorRateManager)                         │ stopSession
                                                      │
  SessionRepository ────────────────────────────────► SessionStorageManager
  (RecordingStateMachine,                             (JSON files + .bin files)
   CircularBuffer<SensorSample>,
   StreamingSessionWriter)
           │ enqueues after stop
           ▼
  WorkManager ──► SessionAnalysisWorker
                        │ calls
                        ▼
              SessionRepository.analyzeRawSession()
                        │ runs pipeline:
                        ├─ LapDetector (LapDetector2)
                        ├─ PeakDetector
                        ├─ SectorDetector
                        ├─ DrivingCoachAnalyzer
                        ├─ CornerCoachingAnalyzer
                        └─ SessionQualityEvaluator
```

---

## Data Flow

### Recording path (real time)

1. The user selects a track and taps Record. `SessionViewModel` calls `context.startRecordingService(trackName)`, which starts `RecordingForegroundService` as a foreground service and acquires a `PARTIAL_WAKE_LOCK` (capped at 20 minutes).
2. `RecordingForegroundService` calls `SensorRecorder.startRecording()`. The recorder begins a 10-second pre-start countdown (`RecordingState.PRESTART_COUNTDOWN`), then a 2-second calibration phase (`RecordingState.CALIBRATING`) during which the accelerometer samples are averaged to determine the gravity vector and derive forward/lateral axes.
3. Once calibration completes, `SessionRepository.startSession()` is called. A `StreamingSessionWriter` is created for the session, and sensor events begin arriving on a dedicated `HandlerThread`. Each raw `SensorEvent` is low-pass filtered, calibrated via `CalibrationManager.projectAcceleration()`, and converted to a `SensorSample`. The sample is written to the `CircularBuffer<SensorSample>` (capacity 1 000) and streamed asynchronously to the binary file via `StreamingSessionWriter.writeSample()`.
4. `AdaptiveSensorRateManager` monitors inter-sample intervals. If more than 30 % of samples are dropped relative to the target rate, the recorder re-registers listeners at the next lower tier (GAME 50 Hz → UI 20 Hz → NORMAL 10 Hz).
5. Every 5 seconds, an autosave coroutine snapshots the `CircularBuffer` contents to a `_partial.json` file via `SessionStorageManager.saveSession()` (atomic write: temp → fsync → rename).
6. `RecordingForegroundService` runs a health watchdog every 2 seconds. If no sensor sample has arrived within 15 seconds of the start of RECORDING, or for 15 seconds after the last sample, the watchdog calls `SessionRepository.markRecordingFailed()`.

### Stop and analysis path (background)

7. When the user taps Stop, `SensorRecorder.stopRecording()` notifies `SessionRepository.stopSession()`. The state machine advances: `STOPPING → SAVING_RAW → RAW_SAVED`.
8. `StreamingSessionWriter.finalize()` flushes the 64 KB in-memory buffer, closes the `FileChannel`, and atomically renames `session_<id>_raw.tmp` to `session_<id>_raw.bin`.
9. A minimal JSON metadata record (no samples) is saved with `processingState = "raw_saved_processing_pending"` and a reference to the `.bin` file path.
10. `SessionRepository.scheduleSessionAnalysis()` enqueues a `OneTimeWorkRequest` for `SessionAnalysisWorker` with the session ID and binary file path as input data. The state machine advances to `RAW_SAVED` and the UI shows "Session saved, analysis scheduled" within ~3 seconds of the user tapping Stop.
11. `SessionAnalysisWorker.doWork()` loads the binary file via `StreamingSessionWriter.loadSamplesFromBinaryFile()`, then calls `SessionRepository.analyzeRawSession()`, which runs the full processing pipeline (steps 12–16) on `Dispatchers.Default`.
12. **Lap detection** — `LapDetector` delegates to `LapDetector2`, which resamples the signal into fixed-duration frames, estimates a lap-time prior (using `TrackProfile` if one exists), generates boundary candidates via `BoundaryGenerator`, finds the globally optimal segmentation path via `GlobalSegmenter`, and materialises `Lap` objects. If the solution is unstable, it falls back to a single-lap result.
13. **Peak and sector detection** — For each `Lap`, `PeakDetector` finds braking and cornering peaks in the smoothed total-acceleration and yaw-rate signals. `SectorDetector` assigns sector boundaries, preferring the stored `TrackProfile` boundaries when they are consistent.
14. **Session validity** — `SessionValidityEvaluator` checks whether the recording looks like an actual driving session (activity ratio, lap count, lap time plausibility). Sessions flagged as `INVALID_NON_DRIVING` are stored but analysis is blocked.
15. **Driving coach analysis** — `DrivingCoachAnalyzer` selects a reference lap, segments it, compares all valid laps segment-by-segment, and produces `CoachingInsight` objects ranked by estimated time loss. It also computes a theoretical best lap time from the best segment times across all laps.
16. **Corner coaching** — `CornerCoachingAnalyzer` uses `AutoCornerDetector` to identify corners in the reference lap, extracts per-corner braking/cornering/exit metrics for every lap, and generates `CornerCoachingInsight` objects with category (ACTION, POSITIVE, CONSISTENCY, CAUTION) and evidence strings.
17. The analysed `Session` (with empty `samples` list — samples remain in the `.bin` file) is saved to JSON and `storedSessions` / `currentSession` `StateFlow`s are updated so the UI reacts immediately.

---

## Key Abstractions

| Class / Type | File | Description |
|---|---|---|
| `SensorSample` | `data/SensorSample.kt` | A single timestamped observation: raw accel (X/Y/Z), raw gyro (X/Y/Z), calibrated longitudinal/lateral/total acceleration, absolute yaw rate |
| `Session` | `data/Session.kt` | The top-level result of a recording: metadata, lap list, coaching outputs, quality score, processing state, and a reference to the binary raw file |
| `Lap` | `data/Lap.kt` | One timed lap with its `SensorSample` slice, braking/cornering peak indices, sector boundaries and times, confidence score, and `LapPhase` |
| `TrackProfile` | `data/TrackProfile.kt` | Aggregated statistical fingerprint of a track, built from multiple sessions: average lap time, average acceleration and yaw-rate curves, typical braking/cornering/sector positions, confidence score |
| `TrackLayout` | `data/TrackLayout.kt` | User-defined spatial metadata: start-line position, direction, named corners, detected corner geometry, optional reference image path |
| `Track` | `data/Track.kt` | Lightweight track record stored in SharedPreferences: name, map image path, start point, direction degrees, optional physical dimensions |
| `RecordingState` | `data/RecordingReliabilityModels.kt` | Enum driving the recording lifecycle state machine (see State Machine section) |
| `RecordingStateMachine` | `data/RecordingStateMachine.kt` | Enforces allowed `RecordingState` transitions; `forceSet` bypasses checks as a last-resort failure path |
| `StreamingSessionWriter` | `data/StreamingSessionWriter.kt` | Real-time binary writer using a 64 KB `ByteBuffer` flushed every second via a coroutine, with atomic temp-to-final rename on close |
| `SessionRepository` | `data/SessionRepository.kt` | Central domain facade; holds the state machine, circular buffer, streaming writer, autosave job, and all `StateFlow` state exposed to the UI |
| `CalibrationManager` | `sensor/CalibrationManager.kt` | Averages accelerometer samples over 2 seconds to compute gravity vector, then derives forward and lateral axes via cross-product; `projectAcceleration()` removes gravity and projects onto the phone-relative movement plane |
| `AdaptiveSensorRateManager` | `sensor/AdaptiveSensorRateManager.kt` | Counts inter-sample drop rate and downgrades from GAME (50 Hz) through UI (20 Hz) to NORMAL (10 Hz) when more than 30 % of samples are below 50 % of the target rate |
| `LapDetector2` | `domain/LapDetector2.kt` | Two-stage lap detection: `BoundaryGenerator` scores candidate start/finish boundaries, `GlobalSegmenter` finds the optimal consistent segmentation path |
| `DrivingCoachAnalyzer` | `domain/DrivingCoachAnalyzer.kt` | Compares all valid laps segment-by-segment against the fastest lap; produces `CoachingInsight` list and theoretical best lap time |
| `CornerCoachingAnalyzer` | `domain/corner/CornerCoachingAnalyzer.kt` | Detects corners automatically on the reference lap, extracts per-corner metrics across all laps, generates `CornerCoachingInsight` objects |
| `SessionStorageManager` | `data/SessionStorageManager.kt` | Atomic JSON persistence; manages `sessions/`, `deleted_sessions/` (soft-delete with 7-day retention), and `corrupt_sessions/` quarantine directories |
| `AppContainer` | `AppContainer.kt` | Manual dependency-injection container; single instance held by `KartingApplication`; wires all domain and data objects |

---

## RecordingState State Machine

```
IDLE ──────────────────────────────────► ABORTED
  │                                        ▲
  ▼                                        │
PRESTART_COUNTDOWN ──────────────────────► ABORTED
  │                         │
  ▼                         ▼
CALIBRATING ──────────────► FAILED
  │
  ▼
RECORDING ─────────────────────────────► FAILED
  │
  ▼
STOPPING ──────────────────────────────► FAILED
  │
  ▼
SAVING_RAW ────────────────────────────► FAILED
  │
  ▼
RAW_SAVED ─────────────────────────────► FAILED
  │  (WorkManager analysis)
  ▼
PROCESSING ────────────────────────────► FAILED
  │
  ▼
COMPLETED ──────────► IDLE / PRESTART_COUNTDOWN / RECORDING

FAILED    ──────────► IDLE / PRESTART_COUNTDOWN
ABORTED   ──────────► IDLE / PRESTART_COUNTDOWN
```

The state machine is owned by `SessionRepository` and driven by `RecordingStateMachine`. Illegal transitions force the state to `FAILED` rather than silently succeeding.

---

## Binary Streaming Format

Sessions are captured to a flat binary file (`sessions/session_<id>_raw.bin`) while recording. The file is written first as `session_<id>_raw.tmp` and atomically renamed on finalisation to prevent corrupt reads.

Each record is 48 bytes, written in Java `ByteBuffer` native byte order:

| Field | Type | Bytes | Description |
|---|---|---|---|
| `timestampNs` | Long | 8 | `SensorEvent.timestamp` in nanoseconds |
| `accelX` | Float | 4 | Raw accelerometer X (m/s²) |
| `accelY` | Float | 4 | Raw accelerometer Y (m/s²) |
| `accelZ` | Float | 4 | Raw accelerometer Z (m/s²) |
| `gyroX` | Float | 4 | Raw gyroscope X (rad/s) |
| `gyroY` | Float | 4 | Raw gyroscope Y (rad/s) |
| `gyroZ` | Float | 4 | Raw gyroscope Z (rad/s) |
| `longitudinalAccel` | Float | 4 | Calibrated forward/backward acceleration |
| `lateralAccel` | Float | 4 | Calibrated left/right acceleration |
| `totalAcceleration` | Float | 4 | Calibrated total lateral G-force magnitude |
| `yawRateAbs` | Float | 4 | Absolute gyroscope vector magnitude (rad/s) |

At 50 Hz the binary file grows at approximately 2.4 KB/s (144 KB/minute). The `StreamingSessionWriter` holds a 64 KB in-memory write buffer flushed to a `FileChannel` every second. Reading is done by `StreamingSessionWriter.loadSamplesFromBinaryFile()` using the same buffer size. JSON metadata files never contain `samples` — the binary file is the sole sample store after recording completes.

---

## WorkManager Analysis Pipeline

```
SessionRepository.finalizeSessionAsync()
    └── StreamingSessionWriter.finalize()         // flush + atomic rename .tmp → .bin
    └── SessionStorageManager.saveSession()       // minimal JSON (no samples)
    └── WorkManager.enqueueUniqueWork(            // ExistingWorkPolicy.KEEP
            "analyze_session_<id>",
            SessionAnalysisWorker
        )

SessionAnalysisWorker.doWork()   [Dispatchers.Default]
    └── StreamingSessionWriter.loadSamplesFromBinaryFile()
    └── SessionRepository.analyzeRawSession()
            ├── LapDetector.detect()
            │       └── LapDetector2 (resample → prior → BoundaryGenerator → GlobalSegmenter)
            ├── PeakDetector.findBrakingPeaks()
            ├── PeakDetector.findCorneringPeaks()
            ├── SectorDetector.detectSectors() / computeSectorTimes()
            ├── SessionValidityEvaluator.evaluate()
            ├── DrivingCoachAnalyzer.analyzeSession()
            └── CornerCoachingAnalyzer.analyze()
    └── SessionStorageManager.saveSession()       // final JSON (no samples)
    └── StateFlow updates → UI reacts
```

`WorkManager` is used rather than a plain coroutine so that analysis survives if the app process is killed between stop and analysis completion. The work item is keyed on `"analyze_session_<id>"` with `ExistingWorkPolicy.KEEP`, preventing duplicate analysis for the same session.

---

## Storage Layout

All storage is under the app's private `filesDir` (not accessible to other apps without root).

```
filesDir/
├── sessions/
│   ├── session_<track>_<epochMs>.json          // final session metadata (no samples)
│   ├── session_<track>_<epochMs>_partial.json  // in-progress autosave
│   └── session_<id>_raw.bin                    // binary sample file (48 bytes × N samples)
├── deleted_sessions/                            // soft-deleted; purged after 7 days
├── corrupt_sessions/                            // quarantined files that failed to parse
├── track_profiles/
│   └── <track>.json                            // TrackProfile (aggregated lap statistics)
├── track_layouts/
│   ├── <track>.json                            // TrackLayout (corner positions, start point)
│   └── images/                                 // imported reference images
└── track_maps/
    └── <track>/
        └── map.png                             // overhead track map image
```

**SharedPreferences** is used for two stores that require fast key-value lookup:

| Preference file | Contents |
|---|---|
| `karting_tracks` | List of track names (serialised JSON array) and per-track `Track` object (serialised JSON) |
| `karting_track_layouts` | Reserved layout preference keys (layout data itself is in JSON files) |
| `karting_track_maps` | Track map metadata (serialised JSON per track) |

All JSON files are written atomically: contents go to a `.tmp` sibling, the file is fsync'd, a `.bak` copy of the previous version is made, and `Files.move` with `ATOMIC_MOVE` replaces the target. If the final file is implausibly small after the move, the backup is restored.

---

## Directory Structure Rationale

```
app/src/main/java/com/kartingtracker/
├── AppContainer.kt          // Manual DI: wires all dependencies; single instance per process
├── KartingApplication.kt    // Application subclass; creates AppContainer on startup
├── data/                    // Data models, storage, and the domain-facing repository
│   ├── Session.kt           // Top-level session data class
│   ├── Lap.kt               // Per-lap data class
│   ├── SensorSample.kt      // Per-sample data class (48-byte binary counterpart)
│   ├── TrackProfile.kt      // Aggregated track statistics
│   ├── TrackLayout.kt       // Track spatial metadata
│   ├── Track.kt             // Lightweight track record
│   ├── RecordingStateMachine.kt  // State transition enforcement
│   ├── RecordingReliabilityModels.kt  // RecordingState enum, RecordingHealth, RecordingIssue
│   ├── StreamingSessionWriter.kt  // Real-time binary file writer + reader
│   ├── SessionStorageManager.kt  // Atomic JSON persistence, quarantine, soft-delete
│   ├── SessionRepository.kt  // Central facade; owns StateFlow state consumed by UI
│   ├── TrackManager.kt       // Track CRUD backed by SharedPreferences
│   ├── TrackProfileManager.kt  // TrackProfile CRUD backed by JSON files
│   ├── TrackLayoutManager.kt  // TrackLayout CRUD backed by JSON files + image files
│   └── TrackMapManager.kt    // Overhead map image management
├── domain/                  // Pure Kotlin analysis algorithms; no Android dependencies
│   ├── LapDetector.kt        // Public entry point; delegates to LapDetector2
│   ├── LapDetector2.kt       // Core detection: resample → prior → boundaries → segmentation
│   ├── BoundaryGenerator.kt  // Scores candidate lap boundaries
│   ├── GlobalSegmenter.kt    // Dynamic-programming global optimisation of boundary path
│   ├── PeakDetector.kt       // Braking and cornering peak detection
│   ├── SectorDetector.kt     // Sector boundary assignment and time computation
│   ├── DrivingCoachAnalyzer.kt  // Segment-level lap comparison; theoretical best lap
│   ├── SessionQualityEvaluator.kt  // Weighted quality score (valid laps, confidence, variance)
│   ├── SessionValidityEvaluator.kt  // Detects non-driving recordings
│   └── corner/
│       └── CornerCoachingAnalyzer.kt  // Per-corner performance metrics and coaching insights
├── sensor/                  // Android sensor integration
│   ├── SensorRecorder.kt     // SensorEventListener; pre-start countdown, calibration, recording
│   ├── CalibrationManager.kt // Gravity removal and forward/lateral axis derivation
│   ├── LowPassFilter.kt      // Exponential smoothing applied to raw sensor events
│   └── AdaptiveSensorRateManager.kt  // Monitors drop rate; downgrades sampling tier
├── service/                 // Android foreground service
│   ├── RecordingForegroundService.kt  // Foreground service; wake lock; health watchdog
│   ├── RecorderWatchdogEvaluator.kt   // Stateless watchdog logic (pure; testable)
│   └── RecordingNotificationHelper.kt  // Notification channel and content builder
├── ui/                      // Fragments, adapters, and ViewModel
│   ├── SessionViewModel.kt   // Single ViewModel for all screens; exposes StateFlow to UI
│   ├── MainActivity.kt       // Single-activity host with Navigation component
│   ├── main/                 // Home / recording screen
│   ├── sessions/             // Session list screen
│   ├── laps/                 // Per-lap telemetry screen
│   ├── comparison/           // Lap-vs-lap comparison with map overlay
│   └── tracklayout/          // Track layout editor
├── util/                    // Cross-cutting utilities (BatteryOptimizationHelper)
└── worker/
    └── SessionAnalysisWorker.kt  // WorkManager CoroutineWorker; triggers off-process analysis
```
