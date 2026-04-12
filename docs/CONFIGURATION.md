<!-- generated-by: gsd-doc-writer -->
# Configuration

This document describes every configuration point in Karting Tracker: the Android build settings,
required device permissions, all hardcoded runtime constants, SharedPreferences stores, and the
on-device file layout.

---

## Build Configuration

### SDK Versions

| Setting | Value |
|---|---|
| `compileSdk` | 34 |
| `minSdk` | 26 (Android 8.0 Oreo) |
| `targetSdk` | 34 |

Devices running Android 7.x (API 25) or earlier cannot install the app. The minimum of API 26
was chosen because `HIGH_SAMPLING_RATE_SENSORS` and the foreground service type `dataSync` require
at least this level in practice.

### Version

| Field | Value |
|---|---|
| `versionCode` | 1 |
| `versionName` | 1.0 |

### JDK / Kotlin Compatibility

| Setting | Value |
|---|---|
| Java source / target compatibility | `VERSION_17` |
| Kotlin JVM target | `JVM_17` |
| Kotlin version (AGP plugin) | 1.9.24 |

A JDK 17 installation is required to build the project. The Gradle daemon is configured with
`-Xmx2048m` heap in `gradle.properties`.

### Gradle Version

The Gradle wrapper pins version **8.14.3** (`gradle/wrapper/gradle-wrapper.properties`). The
Android Gradle Plugin version is **8.5.2** (`build.gradle.kts` root).

### Build Variants

Only the default `debug` and `release` build types are configured. No custom product flavors exist.

| Build Type | Minification |
|---|---|
| `debug` | Off |
| `release` | Off (`isMinifyEnabled = false`) |

ProGuard rules file: `app/proguard-rules.pro`. Minification is disabled even in release; enabling
it would require verifying that Gson reflection on `Session`, `Track`, and related data classes
survives shrinking.

### Build Features

| Feature | Enabled |
|---|---|
| `buildConfig` | Yes |
| `viewBinding` | Yes |

---

## Android Manifest Permissions

All permissions are declared in `app/src/main/AndroidManifest.xml`.

| Permission | When it is needed |
|---|---|
| `FOREGROUND_SERVICE` | Running `RecordingForegroundService` in the foreground |
| `FOREGROUND_SERVICE_HEALTH` | Required on Android 14+ for foreground services with type `dataSync` that involve health/activity data |
| `HIGH_SAMPLING_RATE_SENSORS` | Requesting sensor data at rates above 200 Hz (Android 12+) |
| `POST_NOTIFICATIONS` | Showing the persistent recording notification (Android 13+) |
| `WAKE_LOCK` | Holding a `PARTIAL_WAKE_LOCK` via `PowerManager` to prevent the CPU sleeping during a recording session |

### Hardware Feature Requirements

The manifest declares both as `required="true"`, so devices without these sensors cannot
install the app from the Play Store:

- `android.hardware.sensor.accelerometer`
- `android.hardware.sensor.gyroscope`

---

## Runtime Constants

These values are compiled into the app. Changing them requires a code edit and rebuild.

### Sensor Recording (`SensorRecorder`)

Source: `app/src/main/java/com/kartingtracker/sensor/SensorRecorder.kt`

| Constant | Value | Description |
|---|---|---|
| `PRE_START_COUNTDOWN_SECONDS` | 10 | Countdown shown on screen before calibration begins. The phone must remain still during this window. |

### Adaptive Sensor Rate (`AdaptiveSensorRateManager`)

Source: `app/src/main/java/com/kartingtracker/sensor/AdaptiveSensorRateManager.kt`

The app starts every session at the `GAME` tier and automatically downgrades if too many sample
drops are detected.

**Sampling tiers (in order):**

| Tier | `SensorManager` delay constant | Target rate |
|---|---|---|
| `GAME` (default) | `SENSOR_DELAY_GAME` | 50 Hz |
| `UI` | `SENSOR_DELAY_UI` | 20 Hz |
| `NORMAL` | `SENSOR_DELAY_NORMAL` | 10 Hz |

**Downgrade thresholds:**

| Constant | Value | Meaning |
|---|---|---|
| `DROP_THRESHOLD_RATIO` | 0.5 | A sample whose inter-arrival rate is below 50 % of the target is counted as a drop. |
| `DROP_RATE_THRESHOLD` | 0.3 | Downgrade triggers when more than 30 % of observed samples are drops. |
| `MIN_SAMPLES_BEFORE_DOWNGRADE` | 100 | At least 100 samples must be observed before a downgrade decision is made. |

### Sensor Signal Filtering (`LowPassFilter`)

Source: `app/src/main/java/com/kartingtracker/sensor/LowPassFilter.kt`

| Constant | Value | Description |
|---|---|---|
| `alpha` (default) | 0.18 | IIR low-pass smoothing factor applied to both accelerometer and gyroscope axes. Lower values produce heavier smoothing. |

### Calibration (`CalibrationManager`)

Source: `app/src/main/java/com/kartingtracker/sensor/CalibrationManager.kt`

| Constant | Value | Description |
|---|---|---|
| `calibrationDurationNs` | 2,000,000,000 ns (2 s) | Minimum wall-clock time required during the still phase. |
| `minimumSampleCount` | 30 | Minimum number of accelerometer samples required to finalize calibration. Both conditions must be met. |

### In-Memory Buffer and Autosave (`SessionRepository`)

Source: `app/src/main/java/com/kartingtracker/data/SessionRepository.kt`

| Constant | Value | Description |
|---|---|---|
| `CIRCULAR_BUFFER_SIZE` | 1,000 | Maximum number of `SensorSample` objects held in RAM at once. Oldest samples are evicted when full. Used for autosave snapshots. |
| `AUTOSAVE_INTERVAL_MS` | 5,000 ms (5 s) | How often the in-memory sample ring buffer is persisted to disk during an active recording. |
| `CURRENT_PROCESSING_VERSION` | 9 | Internal version stamp written to every session JSON. Sessions with an older version are automatically reprocessed when loaded. |
| `smoothingWindowSize` | 5 | Moving-average window (in samples) applied to total-acceleration and yaw-rate signals during post-processing. |
| `SLOW_OPERATION_THRESHOLD_MS` | 100 ms | Operations taking longer than this threshold emit a `Log.w` warning. |

**Lap quality thresholds (used in post-processing):**

| Constant | Value |
|---|---|
| `minimumReferenceConfidence` | 0.70 |
| `minimumDisturbedConfidence` | 0.55 |
| `minimumPeaksPerType` | 2 (braking and cornering peaks each) |
| `minimumSectorSpacingPercent` | 10 % |

### Streaming Binary Writer (`StreamingSessionWriter`)

Source: `app/src/main/java/com/kartingtracker/data/StreamingSessionWriter.kt`

| Constant | Value | Description |
|---|---|---|
| `BUFFER_SIZE` | 65,536 bytes (64 KB) | Write-ahead `ByteBuffer` flushed to disk at regular intervals. |
| `SAMPLE_SIZE` | 48 bytes | Fixed binary size of one `SensorSample` record (1× Long + 10× Float). |
| `FLUSH_INTERVAL_MS` | 1,000 ms (1 s) | Background coroutine flushes the write buffer to the channel at this interval. |

**Binary sample layout (48 bytes per record):**

| Field | Type | Bytes | Offset |
|---|---|---|---|
| `timestampNs` | Long | 8 | 0 |
| `accelX` | Float | 4 | 8 |
| `accelY` | Float | 4 | 12 |
| `accelZ` | Float | 4 | 16 |
| `gyroX` | Float | 4 | 20 |
| `gyroY` | Float | 4 | 24 |
| `gyroZ` | Float | 4 | 28 |
| `longitudinalAccel` | Float | 4 | 32 |
| `lateralAccel` | Float | 4 | 36 |
| `totalAcceleration` | Float | 4 | 40 |
| `yawRateAbs` | Float | 4 | 44 |

### Session Storage Limits (`SessionStorageManager`)

Source: `app/src/main/java/com/kartingtracker/data/SessionStorageManager.kt`

| Constant | Value | Description |
|---|---|---|
| `MAX_SESSION_FILE_SIZE_BYTES` | 67,108,864 bytes (64 MB) | Session JSON files larger than this are quarantined as corrupt rather than loaded. |
| `MIN_PLAUSIBLE_JSON_BYTES` | 32 | Minimum byte size for a JSON file to be considered valid. |
| `permanentDeleteAfterMs` | 7 days | Soft-deleted sessions are moved to `deleted_sessions/` and permanently removed after this duration. |

### Foreground Service Timings (`RecordingForegroundService`)

Source: `app/src/main/java/com/kartingtracker/service/RecordingForegroundService.kt`

| Constant | Value | Description |
|---|---|---|
| `NOTIFICATION_UPDATE_INTERVAL_MS` | 1,000 ms | How often the foreground notification refreshes its sample count and elapsed time. |
| `HEALTH_CHECK_INTERVAL_MS` | 2,000 ms | How often the health watchdog coroutine evaluates recording state. |
| `HEARTBEAT_LOG_INTERVAL_MS` | 5,000 ms | Minimum interval between watchdog heartbeat log entries. |
| `SENSOR_STALL_TIMEOUT_MS` | 15,000 ms | If no sensor sample arrives within this window after recording starts, the watchdog stops the session. |
| `WAKELOCK_TIMEOUT_MS` | 1,200,000 ms (20 min) | Maximum `PARTIAL_WAKE_LOCK` hold time per recording session. Long sessions exceeding this may lose the wake lock. |

### Track Profile Manager (`TrackProfileManager`)

Source: `app/src/main/java/com/kartingtracker/data/TrackProfileManager.kt`

| Constant | Value | Description |
|---|---|---|
| `PROFILE_POINT_COUNT` | 101 | Number of points in the normalized lap-signal arrays stored in track profiles. |
| `minimumLapTimeMs` | 15,000 ms (15 s) | Sessions with an average lap time below this are rejected from profile updates. |
| `maximumLapTimeMs` | 120,000 ms (120 s) | Sessions with an average lap time above this are rejected from profile updates. |
| `minimumValidLapsPerSession` | 3 | Sessions with fewer clean laps do not contribute to the track profile. |
| `profileUpdateBaseInfluence` | 0.2 | Blend factor controlling how much a new session shifts the existing profile. |
| `profileConfidenceGrowthFactor` | 0.1 | How much a good-quality session increases the profile confidence score (max 1.0). |
| `matureProfileThreshold` | 0.7 | Profiles above this confidence require higher-quality sessions to be updated. |

### Backup Format (`AppBackupManager`)

Source: `app/src/main/java/com/kartingtracker/data/AppBackupManager.kt`

| Constant | Value | Description |
|---|---|---|
| `BACKUP_FORMAT_VERSION` | 1 | Written to the backup ZIP manifest. Import rejects archives with a different version number. |

### Bundled Track Asset Versions

| Manager | Constant | Value |
|---|---|---|
| `TrackLayoutManager` | `bundledLayoutVersion` | 9 |
| `TrackMapManager` | `bundledMapVersion` | 4 |

On first launch (or after an app update that bumps these values), bundled track assets from
`assets/preloaded_tracks/manifest.json` are copied to the internal file store.

---

## SharedPreferences Stores

No passwords, API keys, or secrets are stored. All preferences hold user-selected state.

| Store name | Created by | Contents |
|---|---|---|
| `karting_tracks` | `TrackManager` | Set of track names (`KEY_TRACKS = "tracks"`); per-track JSON blobs keyed as `"track_{sanitized_name}"`; selected track name (`KEY_SELECTED_TRACK = "selected_track"`). |
| `karting_track_layouts` | `TrackLayoutManager` | Bundled layout version stamp (`KEY_BUNDLED_LAYOUT_VERSION = "bundled_layout_version"`). |
| `karting_track_maps` | `TrackMapManager` | Bundled map version stamp (`KEY_BUNDLED_MAP_VERSION = "bundled_map_version"`). |

All stores use `Context.MODE_PRIVATE`.

---

## On-Device File Layout

All persistent data lives under the app's private internal storage root (`context.filesDir`,
typically `/data/data/com.kartingtracker/files/`). No external storage or `WRITE_EXTERNAL_STORAGE`
permission is used.

```
<filesDir>/
  sessions/
    session_<track>_<epochMs>.json          # Finalized session metadata (no samples)
    session_<track>_<epochMs>_partial.json  # Autosave snapshot (in-progress)
    session_<sessionId>_raw.bin             # Binary sensor stream (48 bytes × N samples)
    session_<sessionId>_raw.tmp             # Temp file during active streaming (renamed on finalize)
  deleted_sessions/
    session_<track>_<epochMs>.json          # Soft-deleted; purged after 7 days
  corrupt_sessions/
    <original_name>_<epochMs>.json          # Quarantined files that failed validation
  track_profiles/
    track_<sanitized_track_name>.json       # Learned lap-time and acceleration profile per track
  track_layouts/
    layout_<sanitized_track_name>.json      # User-configured corner and start-point layout
    images/
      layout_<sanitized_track_name>.<ext>   # User-uploaded track map image (jpg/png/webp)
  track_maps/
    <sanitized_track_name>/
      map.png                               # Rendered track map bitmap
      metadata.json                         # Curve definitions and track metadata
```

**Session file naming:** track names are sanitized via `FileNameNormalizer.normalize()` before use
in file names, replacing spaces and special characters with underscores.

**Backup scope:** `AppBackupManager.exportBackup()` packages `sessions/`, `track_layouts/`,
`track_maps/`, `track_profiles/`, and the `shared_prefs/` directory into a ZIP archive.
Debug tracks (`Test Track` and `Demo Indoor Track`) are excluded from exports. Note: `sr test` is
suppressed in the track selection UI via `TrackManager.isSuppressedDebugTrack()` but is not
excluded from backup archives.

---

## Recording State Machine

The `RecordingStateMachine` enforces strict state transitions to prevent data loss. States are
exposed as `RecordingState` enum values and observed by the UI and the foreground service.

```
IDLE
  └─> PRESTART_COUNTDOWN  (10-second still countdown)
        └─> CALIBRATING        (2-second gravity calibration)
              └─> RECORDING    (active sensor capture)
                    └─> STOPPING
                          └─> SAVING_RAW    (binary file flush + rename)
                                └─> RAW_SAVED  (WorkManager analysis job queued)
                                      └─> PROCESSING  (background analysis)
                                            └─> COMPLETED
                                                  └─> IDLE

  Error exits to FAILED or ABORTED from most states.
  FAILED / ABORTED → IDLE or PRESTART_COUNTDOWN
```

`RecordingIssue` enum values reported by the health watchdog:

| Issue | Trigger |
|---|---|
| `RECORDER_DEAD` | Service alive but recorder not active |
| `SAMPLE_STALL` | No sensor samples received for `SENSOR_STALL_TIMEOUT_MS` (15 s) |
| `NOT_FOREGROUND` | Service lost foreground status |
| `NO_WAKE_LOCK` | Wake lock released unexpectedly |
| `BATTERY_OPTIMIZATION` | Battery optimization may kill the app |
