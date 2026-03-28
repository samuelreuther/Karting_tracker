# Karting Tracker

Android app for indoor karting telemetry with smartphone sensors only. No GPS is required.

The app records accelerometer and gyroscope data, processes the signal on-device, detects laps heuristically, stores sessions as JSON files, and lets you compare laps visually.

## Current Scope

This is a practical usable app version, not a throwaway prototype.

Implemented today:

- recording with accelerometer and gyroscope at `SENSOR_DELAY_FASTEST`
- 2-second calibration before recording
- low-pass filtering
- gravity removal
- derived signals for both chart compatibility and pocket-tolerant detection
- hybrid lap detection with correlation, event detection, confidence scoring, and outlap handling
- braking and cornering peak markers
- lap comparison with overlay charts, delta charts, and simple text insights
- persistent session storage as JSON
- periodic autosave during recording
- track management
- session browser with filter and reload
- load-last-session shortcut

Not implemented yet:

- foreground service for long background-safe recording
- export to CSV or external share target
- fully orientation-invariant directional charts
- sector timing
- automated tests
- verified build/run in this environment

## How The App Works

### Recording

When you press `Start`, recording does not begin immediately.

The app first enters a calibration phase for about 2 seconds:

- it assumes the kart is stationary
- it averages accelerometer samples
- it estimates the gravity vector
- it derives a driving plane from that gravity vector

After calibration:

- accelerometer and gyroscope events are filtered
- gravity is removed from accelerometer readings
- the app stores timestamped `SensorSample` entries

Stored signals include:

- raw accelerometer values `accelX`, `accelY`, `accelZ`
- raw gyroscope values `gyroX`, `gyroY`, `gyroZ`
- compatibility signals:
  - `longitudinalAccel`
  - `lateralAccel`
- robust detection signals:
  - `totalAcceleration`
  - `yawRateAbs`

`yawRateAbs` is computed as the magnitude of the full gyroscope vector, not just `gyroZ`.

### Lap Detection

Lap detection uses orientation-tolerant signals:

- `totalAcceleration`
- `yawRateAbs`

Current detection flow:

1. resample the session into 100 ms buckets
2. compare sliding windows with cosine similarity
3. require braking-like and cornering-like events
4. compute a confidence score from correlation, event presence, and duration consistency
5. reject implausible lap lengths outside 15-120 seconds
6. mark an unstable first lap as outlap if needed
7. filter unstable remaining laps
8. fall back to a single lap if detection is not reliable

Outlap handling:

- the first detected lap is checked separately
- if its duration differs strongly from later laps, or its confidence is low, it is marked as outlap
- outlaps remain visible in the app
- outlaps are excluded from default comparison selection when stable laps exist

### Comparison

Lap comparison still uses the compatibility signals:

- longitudinal acceleration chart
- lateral acceleration chart

For comparison, laps are normalized to 0-100 percent using linear interpolation with 251 points by default.

The comparison screen shows:

- Lap A and Lap B overlay for longitudinal acceleration
- Lap A and Lap B overlay for lateral acceleration
- delta graph for longitudinal and lateral difference
- braking markers
- cornering markers
- short heuristic driving insights

## Persistence

Sessions are stored permanently on the device as JSON files.

Storage details:

- location: app-specific storage under `filesDir/sessions`
- file naming: `session_<trackName>_<startTimeEpochMs>.json`
- each recording overwrites the same session file during autosave
- a final save happens again when recording stops

Each saved session contains:

- session id
- track name
- start and end times
- all recorded samples
- detected laps
- lap times
- braking and cornering peak indices

Crash protection:

- during recording, autosave runs every 5 seconds
- if a session was saved only partially and has no laps yet, the repository reprocesses it on load

## Track Management

Before starting a session, the user can:

- select an existing track
- create a new track from a dialog

Tracks are stored in `SharedPreferences`.

The currently selected track is also persisted, so the app reopens with the previous selection.

## Session Browser

The app includes a session list screen with:

- all stored sessions
- filter by track
- open laps
- open comparison

There is also a `Load last session` button on the main screen.

When a saved session is loaded:

- the repository sets it as `currentSession`
- the lap list is refreshed
- comparison state is recomputed
- default lap selection prefers non-outlap laps

## Project Structure

- `app/src/main/java/com/kartingtracker/data`
  - data models, repository, session storage, track manager
- `app/src/main/java/com/kartingtracker/sensor`
  - sensor capture, calibration, low-pass filter
- `app/src/main/java/com/kartingtracker/domain`
  - lap detection, peak detection, normalization, insights
- `app/src/main/java/com/kartingtracker/ui`
  - shared view model and UI state
- `app/src/main/java/com/kartingtracker/ui/main`
  - main recording screen
- `app/src/main/java/com/kartingtracker/ui/laps`
  - lap list screen
- `app/src/main/java/com/kartingtracker/ui/comparison`
  - comparison screen
- `app/src/main/java/com/kartingtracker/ui/sessions`
  - saved session browser

## Install In Android Studio

1. Open the repository root in Android Studio.
2. Let Android Studio install the Gradle wrapper distribution and project dependencies.
3. Sync the project.
4. Connect an Android device with accelerometer and gyroscope.
5. Run the `app` configuration.

## Install On A Samsung Phone

### Recommended: install directly from Android Studio

1. Open `Settings`.
2. Go to `About phone` -> `Software information`.
3. Tap `Build number` 7 times.
4. Go back to `Settings` -> `Developer options`.
5. Enable `USB debugging`.
6. Connect the phone to the development machine.
7. Accept the USB debugging authorization prompt on the phone.
8. Select the phone in Android Studio.
9. Run the app from Android Studio.

### Alternative: install an APK manually

1. In Android Studio, build an APK from `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
2. Copy the APK to the Samsung phone.
3. Open it on the phone.
4. If needed, allow `Install unknown apps` for the file manager or browser used to open the APK.
5. Confirm installation.

## First Use On The Phone

Recommended practical flow:

1. Open the app.
2. Select an existing track or create a new one.
3. Place the phone in the kart mount or in a consistent pocket position.
4. Keep the kart still.
5. Press `Start`.
6. Wait through the calibration phase.
7. Drive the session.
8. Press `Stop`.
9. Open `View detected laps` to inspect detected laps.
10. Open `Compare laps` to compare stable laps.

## Pocket Usage Notes

The app is more tolerant than before, but not fully orientation-free.

What is robust today:

- gravity removal
- total acceleration for event strength
- yaw magnitude for rotation intensity
- lap detection using `totalAcceleration` and `yawRateAbs`

What still depends on approximate orientation:

- `longitudinalAccel`
- `lateralAccel`
- the chart overlays and the current text insights that use those chart signals

Practical advice:

- keep phone placement consistent between sessions
- avoid moving the phone after calibration
- keep the kart fully still during calibration
- expect lap detection to be more robust than directional chart interpretation when the phone is loose in a pocket

## Known Limitations

- no foreground service, so Android may interrupt longer sessions if the app is backgrounded aggressively
- no export outside app storage
- no database
- no sector or split analysis
- no fully sensor-fused 3D orientation estimation
- lap detection is heuristic and not validated against reference timing hardware
- comparison charts still use derived longitudinal/lateral axes, not purely orientation-invariant signals

## Documentation

- detailed technical documentation: `docs/PROJEKTDOKUMENTATION.md`

## Build Status In This Workspace

The local environment used for these changes does not include Java, Gradle, or Android SDK tooling, so no real Android build or device run was executed here.
