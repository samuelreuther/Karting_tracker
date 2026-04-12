<!-- generated-by: gsd-doc-writer -->
# TESTING.md

This document covers the automated test suite for Karting Tracker, how to run each category of tests, what each test class verifies, and the manual device validation checklist for recorder reliability.

---

## Test Framework and Setup

The project uses **JUnit 4** (`junit:junit:4.13.2`) for all unit tests, with `kotlinx-coroutines-test:1.8.1` available for coroutine-based test scenarios.

For instrumented Android tests the runner is `androidx.test.runner.AndroidJUnitRunner`, backed by `androidx.test.ext:junit:1.2.1` and `espresso-core:3.6.1`. No instrumented tests are currently present in `app/src/androidTest/`; the test suite consists entirely of JVM unit tests under `app/src/test/`.

No additional setup beyond a standard Android Studio / Gradle sync is required before running unit tests.

---

## Running Tests

### Full unit test suite

```bash
./gradlew :app:testDebugUnitTest
```

Runs every test class under `app/src/test/` against the debug build variant.

### Single test class

```bash
./gradlew :app:testDebugUnitTest --tests com.kartingtracker.domain.ReliabilityWorkflowTest
```

Replace the fully-qualified class name to target any individual test class.

### Single test method

```bash
./gradlew :app:testDebugUnitTest --tests "com.kartingtracker.domain.ReliabilityWorkflowTest.threeSessionReliabilityWorkflowProducesAnalysisAndStableVisualizationData"
```

### From Android Studio

Open any test file, click the gutter icon next to a class or method, and select **Run**.

---

## Test Classes

### `ReliabilityWorkflowTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/ReliabilityWorkflowTest.kt`

The primary end-to-end reliability test. It simulates three complete sessions using `SimulatedSessionGenerator.generateSeededSession` with deterministic seeds and the following durations:

| Session | Seed | Duration |
|---------|------|----------|
| 1 | 42 | 8 minutes |
| 2 | 1337 | 12 minutes |
| 3 | 9001 | 15 minutes |

For each session the test runs the full processing pipeline — lap detection, sector detection, braking and cornering peak detection — then asserts:

- **Lap detection** returns at least one lap.
- **Time-loss curve** is non-empty and contains no `NaN` or `Infinite` values (uses the two fastest laps as the comparison pair).
- **Coaching insights** and **segment markers** are non-empty.

Run it in isolation:

```bash
./gradlew :app:testDebugUnitTest --tests com.kartingtracker.domain.ReliabilityWorkflowTest
```

---

### `SimulatedSessionGeneratorTrackAwareTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/SimulatedSessionGeneratorTrackAwareTest.kt`

Tests track-aware seeded session generation using `LapDetector2`:

- `loerrachSeededSessionProducesStableLapAndCoachingSignals` — generates a 10-minute seeded session for `"Loerrach VM Kart Racing"` (seed `42`) and asserts approximately 22-26 laps are detected, sector boundaries are present on at least one lap, and the `DrivingCoachAnalyzer` returns both coaching insights and segment markers.
- `seededGenerationIsDeterministicAcrossRuns` — generates the same session twice with identical parameters and asserts equal sample counts, timestamps, and `yawRateAbs` profiles.

---

### `RecordingStateMachineTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/RecordingStateMachineTest.kt`

Verifies the state machine that guards the recording lifecycle:

- The valid reliability path `IDLE → PRESTART_COUNTDOWN → CALIBRATING → RECORDING → STOPPING → SAVING_RAW → PROCESSING → COMPLETED` is accepted in full.
- An impossible direct transition (`IDLE → PROCESSING`) is rejected and the state remains `IDLE`.

---

### `StreamingSessionWriterTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/StreamingSessionWriterTest.kt`

Tests binary streaming writes and reads for the raw session file:

- A single sample produces a 48-byte file (48 bytes per `SensorSample`).
- 100 samples produce a 4,800-byte file, and `loadSamplesFromBinaryFile` reads back all field values correctly.
- Finalizing with no samples creates an empty file and reports `samplesWritten == 0`.
- `samplesWritten` counter matches the number of samples passed in.
- All floating-point fields round-trip through the binary format without precision loss beyond `0.001f`.

Uses `org.junit.rules.TemporaryFolder` for file isolation.

---

### `RawPersistenceGuardTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/RawPersistenceGuardTest.kt`

Verifies the guard that prevents unsafe raw-file operations:

- `shouldPersistAutosave(0)` returns `false`; `shouldPersistAutosave(1)` returns `true`.
- A session with `processingState == PROCESSING_STATE_FAILED` is allowed to finalize from raw (`rawCanFinalize` returns `true`).

---

### `CircularBufferTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/CircularBufferTest.kt`

Tests the bounded circular buffer used for rolling sensor windows:

- Adding under capacity: size and order are correct.
- Adding over capacity: oldest element is evicted.
- `clear` empties the buffer.
- `latest` returns the most recently added element and `null` on an empty buffer.
- Multiple full wraps preserve only the last `capacity` elements.

---

### `FileNameNormalizerTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/FileNameNormalizerTest.kt`

- Umlauts are transliterated: `"Lörrach VM Kart Racing"` → `"Loerrach_VM_Kart_Racing"`.
- Unsafe characters and repeated separators are collapsed: `" Track   One/Layout "` → `"Track_One_Layout"`.

---

### `TrackNameCanonicalizerTest` (data)

**File:** `app/src/test/java/com/kartingtracker/data/TrackNameCanonicalizerTest.kt`

- Legacy variants `"L_rrach VM Kart Racing"`, `"Loerrach VM Kart Racing"`, `"Lörrach"`, `"Loerrach"`, and `"L_rrach"` all canonicalize to the human-readable display name `"Lörrach VM Kart Racing"`.
- `possibleStorageKeys` returns both legacy storage keys (`Loerrach_VM_Kart_Racing`, `L_rrach_VM_Kart_Racing`) to support backward-compatible file loading.

---

### `SessionValidityEvaluatorTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/SessionValidityEvaluatorTest.kt`

- A session with near-zero acceleration and a single low-confidence interrupted lap is classified as `INVALID_NON_DRIVING`, with non-empty diagnostics.
- A session with realistic acceleration variance, 3 normal laps at `confidenceScore 0.82`, and 8 boundary candidates is classified as `VALID`.

---

### `CornerCoachingAnalyzerTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/CornerCoachingAnalyzerTest.kt`

- Returns empty insights when fewer than the required number of usable laps are present (one high-confidence lap, one below threshold).
- Returns non-empty corner insights and a `summary` with at most 3 top actions when the session contains 3 laps with varying braking shift and exit penalty profiles.

---

### `CurveDetectorTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/CurveDetectorTest.kt`

- Detects exactly 2 curves from a synthetic lap with two Gaussian `yawRateAbs` peaks.
- Peak positions fall within expected normalized lap-position ranges (15–30 % and 56–70 %).
- All detected curves have `intensity > 0.2`.

---

### `LapDetectionDebugInfoTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/LapDetectionDebugInfoTest.kt`

- `LapDetector.detect` on a 2-sample session sets `debugInfo.fallbackToSingleLap = true`, returns non-empty `fallbackReasons`, and yields exactly 1 lap.

---

### `MapOverlayProjectorTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/MapOverlayProjectorTest.kt`

- Without a track layout, `projectCurves` falls back to perimeter approximation for 3 curves.
- Projected labels are `["T1", "T2", "T3"]`.
- All projected positions fall within the `[0.12, 0.88]` range on both axes.

---

### `TrackCornerTypeDetectorTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/TrackCornerTypeDetectorTest.kt`

- An oval centerline with three curvature spikes of varying strength produces at least one `TIGHT`, one `MEDIUM`, and one `FAST` corner.
- Detection is deterministic: two calls with identical input return equal lists.

---

### `TrackLayoutMapperTest` (domain)

**File:** `app/src/test/java/com/kartingtracker/domain/TrackLayoutMapperTest.kt`

- `sortAndRenameCorners` preserves the manual corner order from the saved layout and renames them to `"Kurve 1"`, `"Kurve 2"`, `"Kurve 3"`.
- `buildCornerReferences` maps 3 detected corners to the 3 saved layout corners with correct `insightLabel` values.

---

### `AdaptiveSensorRateManagerTest` (sensor)

**File:** `app/src/test/java/com/kartingtracker/sensor/AdaptiveSensorRateManagerTest.kt`

- Default rate on construction is `GAME`.
- `calculateActualRate` computes Hz correctly from a 20 ms interval as 50 Hz.
- `shouldDowngrade` returns `false` for a stable 50 Hz stream and `false` when fewer than 100 samples have been observed.
- `shouldDowngrade` returns `true` when the sample drop rate exceeds 30 %.
- `downgrade` transitions `GAME → UI → NORMAL` and returns `false` when already at `NORMAL`.
- Downgrade resets the internal drop counters.
- `reset` restores the manager to the full initial state.

---

### `RecorderWatchdogEvaluatorTest` (service)

**File:** `app/src/test/java/com/kartingtracker/service/RecorderWatchdogEvaluatorTest.kt`

- Detects a "no first sample" condition when `RECORDING` state has been active for more than `stallTimeoutMs` without any sample arriving.
- Detects a "stalled" condition when the last sample timestamp is more than `stallTimeoutMs` in the past.
- Returns `null` (no watchdog fire) for a healthy session where the last sample is recent.

---

## Coverage Thresholds

No coverage threshold is configured in `app/build.gradle.kts`. There is no `.nycrc` or `c8` configuration. Coverage is not enforced automatically.

---

## Physical Device vs Emulator

| Scenario | Physical device required? |
|---|---|
| All JVM unit tests (`app:testDebugUnitTest`) | No — runs on the host JVM |
| Instrumented tests (`app:connectedAndroidTest`) | Yes — an Android device or emulator is needed; no instrumented tests exist yet |
| Realistic IMU telemetry recording | Yes — emulators do not produce realistic accelerometer/gyroscope signals for lap detection validation |
| Foreground service reliability (battery policy, wake lock) | Yes — OEM battery restrictions only apply on real devices |
| Manual checklist scenarios (A–G) | Yes — physical device required |

The README notes: "A physical Android device [is required] for realistic sensor testing (emulator works for UI, not for realistic IMU telemetry)."

---

## Manual Reliability Validation Checklist

The file `docs/RELIABILITY_VALIDATION_CHECKLIST.md` contains a step-by-step checklist for validating recorder reliability on a physical Android phone. It requires a debug APK with logcat access, a valid track selected, and foreground notification permission granted.

### Preconditions

- Build a debug APK with logcat access.
- Select a valid track.
- Confirm foreground notification permissions are granted.

### A) 3-minute simulated session

1. Run the simulator for 3 minutes from the debug action.
2. Verify logs include: `entered RECORDING`, first sample after RECORDING, heartbeat logs every few seconds, autosave success with file path and byte size, raw final save success with file path, byte size, and sample count, processing start and processing end.
3. Verify the diagnostics panel shows a non-empty raw path and raw status.

### B) 10-minute simulated session

1. Run the simulator for 10 minutes.
2. Verify repeated heartbeat and autosave success logs.
3. Verify no `FAILED`/`ABORTED` state unless intentionally induced.

### C) 5-minute real pocket/background recording

1. Start recording on a track, lock the screen, and keep the phone in a pocket.
2. Wait at least 5 minutes.
3. Verify the service remains foreground and heartbeat logs continue.
4. Return to the app and confirm the state is still `RECORDING` with a rising sample count.

### D) Return to app mid-recording

1. During active recording, open the app from the notification.
2. Verify the UI state and diagnostics reflect the active session (state, sample count, sample ages, watchdog active).

### E) Stop and verify raw persistence even if processing fails

1. Stop recording.
2. Confirm the raw final save success log appears before processing start.
3. If processing fails: confirm state is `FAILED` (explicit), the raw file path remains present, and the raw file is non-empty and reprocessable.

### F) Force processing failure and verify raw survives

1. Use a debug/instrumented build to throw after raw save and before the final processed save.
2. Confirm logs show: `entered SAVING_RAW`, raw final save success, processing start, processing end with failed state/reason.
3. Confirm the raw file still exists and can be loaded/reprocessed.

### G) Force sensor stall and verify watchdog reason

1. Start recording and inject or induce a sensor callback freeze.
2. Confirm heartbeat stops advancing the sample age and the watchdog fires.
3. Confirm the explicit watchdog stop reason is visible in logs and the diagnostics panel.

---

## CI Integration

No CI/CD pipeline is configured in this repository. Tests must be run locally via Gradle or Android Studio.
