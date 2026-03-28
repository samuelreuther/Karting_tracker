# Karting Tracker

Android app for indoor karting telemetry with smartphone sensors only. No GPS is required.

The app records accelerometer and gyroscope data, processes the signal on-device, detects laps heuristically, stores sessions as JSON files, and lets you compare laps visually.

## Current Scope

This is a practical usable app version, not a throwaway prototype.

Implemented today:

- recording with accelerometer and gyroscope at `SENSOR_DELAY_FASTEST`
- foreground service based recording with persistent notification
- 2-second calibration before recording
- low-pass filtering
- gravity removal
- derived signals for both chart compatibility and pocket-tolerant detection
- hybrid lap detection with correlation, event detection, confidence scoring, track-profile biasing, and outlap handling
- distinction between `OUTLAP` and `DISTURBED` laps
- automatic sector detection and sector time calculation without GPS
- braking and cornering peak markers
- lap comparison with overlay charts, a time loss chart, sector deltas, and simple text insights
- persistent session storage as JSON
- periodic autosave during recording
- track management
- track-specific learning with reusable profiles
- session browser with filter and reload
- load-last-session shortcut
- debug seeding of one simulated test session

Not implemented yet:

- export to CSV or external share target
- fully orientation-invariant directional charts
- best-sector or ideal-lap analysis across many laps
- full live recording recovery after process death or device reboot
- automated tests
- verified build/run in this environment

## Example Screenshots

These are illustrative example images generated from the app's simulated telemetry and current UI concepts.

### Comparison Example

![Example comparison with longitudinal, lateral and time loss charts](docs/images/example_comparison_time_loss.svg)

### Session Stats Example

![Example session stats with learned track profile and lap quality tags](docs/images/example_session_stats.svg)

## How The App Works

### Recording

When you press `Start`, recording does not begin immediately.

The app first starts a foreground service:

- the service promotes itself with a persistent notification within the required startup window
- the notification shows the current track, elapsed time, and sample count
- a stop action in the notification stops recording cleanly
- the service keeps running when the UI goes to background
- a partial wake lock is held while recording to improve reliability when the screen turns off

The app first enters a calibration phase for about 2 seconds:

- it assumes the kart is stationary
- it averages accelerometer samples
- it estimates the gravity vector
- it derives a driving plane from that gravity vector

After calibration:

- accelerometer and gyroscope events are filtered
- gravity is removed from accelerometer readings
- the app stores timestamped `SensorSample` entries
- sensor ownership stays in the foreground service, not in the activity lifecycle

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
4. if a learned track profile exists, restrict the search range around the expected lap time
5. bias the score with similarity to the learned average track pattern
6. boost likely candidates when detected events align with learned braking and cornering zones
7. compute a confidence score from correlation, event presence, and duration consistency
8. reject implausible lap lengths outside 15-120 seconds
9. mark an unstable first lap as outlap if needed
10. classify bad laps as disturbed if they are too slow, low-confidence, or missing enough peaks
11. filter unstable remaining laps
12. fall back to a single lap if detection is not reliable

Outlap handling:

- the first detected lap is checked separately
- if its duration differs strongly from later laps, or its confidence is low, it is marked as outlap
- outlaps remain visible in the app
- outlaps are excluded from default comparison selection when stable laps exist

Disturbed lap handling:

- disturbed laps are separate from outlaps
- a lap is marked disturbed when it is much slower than the session reference, has low confidence, or contains too few braking/cornering peaks
- disturbed laps stay visible in the session, but the app avoids auto-selecting them for comparison when cleaner laps exist

### Comparison

Lap comparison still uses the compatibility signals:

- longitudinal acceleration chart
- lateral acceleration chart

For comparison, laps are normalized to 0-100 percent using linear interpolation with 251 points by default.

The comparison screen shows:

- Lap A and Lap B overlay for longitudinal acceleration
- Lap A and Lap B overlay for lateral acceleration
- time loss graph for `Lap A - Lap B`
- sector-by-sector delta lines
- braking markers
- cornering markers
- short heuristic driving insights

Time loss:

- uses normalized `totalAcceleration`
- smooths the signal first
- normalizes each lap to zero mean and unit variance
- integrates a bounded velocity estimate with drift correction
- builds time over the same normalized distance axis for both laps
- compares both laps point by point on that aligned axis
- positive values mean Lap A is slower at that point
- negative values mean Lap A is faster
- falls back toward a pattern-alignment curve when signal confidence is low
- internally also exposes a `TimeLossResult` with `deltaCurve` and `confidence`

Sector detection:

- uses normalized `totalAcceleration` and `yawRateAbs`
- detects strong braking dips and cornering peaks
- reduces them to 1-3 stable internal boundaries
- yields 2-4 sectors per lap
- falls back to a simple midpoint split if too few stable events are found
- if a track profile exists, the app reuses learned sector boundaries for consistency across sessions

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
- disturbed and outlap flags
- sector boundaries and sector times

Crash protection:

- during recording, autosave runs every 5 seconds
- if a session was saved only partially and has no laps yet, the repository reprocesses it on load
- if an older saved session has laps but no sector metadata yet, the repository enriches it on load
- the foreground service keeps recording active while the app is backgrounded or the screen is off

## Track Management

Before starting a session, the user can:

- select an existing track
- create a new track from a dialog

Tracks are stored in `SharedPreferences`.

The currently selected track is also persisted, so the app reopens with the previous selection.

## Track-Specific Learning

The app builds a `TrackProfile` for each track from previous sessions.

What is stored per track:

- average lap time
- lap-time spread
- average lap sample length
- average normalized `totalAcceleration`
- average normalized `yawRateAbs`
- typical braking zones
- typical cornering zones
- typical sector boundaries
- number of sessions used for learning

How it is used:

- the profile is saved under `filesDir/track_profiles`
- after a session stops, the profile for that track is rebuilt from historical sessions
- new sessions on the same track use that profile immediately during lap detection
- new laps on the same track reuse learned sector boundaries when available
- profiles with fewer than 2 sessions have lower influence

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
- default lap selection prefers laps that are neither outlap nor disturbed
- missing sector metadata is enriched on load
- older lap classifications are refreshed on load when needed

## Simulated Test Data

For debug builds, the app seeds one simulated session once on app start:

- track name: `Test Track`
- persistent JSON save through `SessionStorageManager`
- slower first lap marked as outlap
- additional disturbed-lap handling still applies after load/classification
- multiple laps with braking and cornering markers

This is intended for UI and chart validation when no real kart session has been recorded yet.

## Project Structure

- `app/src/main/java/com/kartingtracker/data`
  - data models, repository, session storage, track manager, track profiles, simulated data
- `app/src/main/java/com/kartingtracker/sensor`
  - sensor capture, calibration, low-pass filter
- `app/src/main/java/com/kartingtracker/service`
  - foreground service, notification helper, start/stop helpers
- `app/src/main/java/com/kartingtracker/domain`
  - lap detection, sector detection, peak detection, normalization, time loss, insights
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
2. Allow notifications on Android 13+ when prompted.
3. Select an existing track or create a new one.
4. Place the phone in the kart mount or in a consistent pocket position.
5. Keep the kart still.
6. Press `Start`.
7. Wait through the calibration phase.
8. Drive the session.
9. If needed, leave the app in background while the foreground notification stays visible.
10. Press `Stop` in the app or from the notification action.
11. Open `View detected laps` to inspect detected laps.
12. Open `Compare laps` to compare stable laps.

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
- keep notification permission enabled so the recording service remains user-visible
- expect lap detection to be more robust than directional chart interpretation when the phone is loose in a pocket

## Known Limitations

- no export outside app storage
- no database
- no ideal-lap or best-sector aggregation across sessions
- no fully sensor-fused 3D orientation estimation
- recording continuity is greatly improved in background, but a killed process cannot fully reconstruct a live sensor stream mid-session
- background behavior still depends partly on OEM battery policies despite the foreground service
- lap detection is heuristic and not validated against reference timing hardware
- comparison charts still use derived longitudinal/lateral axes, not purely orientation-invariant signals
- time loss is a lightweight approximation from acceleration, not a GPS or transponder-based ground truth
- sector boundaries are heuristic and not beacon or GPS sectors

## Documentation

- detailed technical documentation: `docs/PROJEKTDOKUMENTATION.md`
- illustrative example assets: `docs/images/`

## Documentation Policy

Project documentation is intended to stay in sync with code changes after each implemented feature change.

## Build Status In This Workspace

The local environment used for these changes does not include Java, Gradle, or Android SDK tooling, so no real Android build or device run was executed here.
