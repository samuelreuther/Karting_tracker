# Karting Tracker

Android MVP for indoor karting analysis using accelerometer and gyroscope data only.

## What it does

- Records accelerometer and gyroscope data at `SENSOR_DELAY_FASTEST`
- Runs a 2-second calibration step before recording
- Applies a simple low-pass filter and removes gravity
- Derives longitudinal and lateral acceleration after calibration
- Detects repeating lap boundaries with hybrid correlation and event detection
- Stores sessions and laps in memory
- Lists detected laps with lap times
- Normalizes laps to 0-100 percent with interpolation and overlays them with MPAndroidChart
- Shows braking and cornering markers, a delta graph, and simple driving insights

## Device assumption

The current MVP assumes the phone is mounted stably and roughly aligned with the kart.
It no longer depends on fixed raw device axes alone because it calibrates gravity before recording.

- Gravity vector is estimated during the initial stationary calibration
- Forward direction is approximated from the device orientation projected onto the driving plane
- Best results still require a repeatable mount position

## Project structure

- `app/src/main/java/com/kartingtracker/data` data models and repository
- `app/src/main/java/com/kartingtracker/domain` lap detection, normalization, peak detection
- `app/src/main/java/com/kartingtracker/sensor` sensor capture and filtering
- `app/src/main/java/com/kartingtracker/ui` activity, shared view model, UI state
- `app/src/main/java/com/kartingtracker/ui/main` recording screen
- `app/src/main/java/com/kartingtracker/ui/laps` lap list screen
- `app/src/main/java/com/kartingtracker/ui/comparison` lap comparison screen

## Open in Android Studio

1. Open the repository root.
2. Let Android Studio download the Gradle distribution and Android dependencies.
3. Sync the project.
4. Run on a device with accelerometer and gyroscope sensors.

## Install On A Samsung Phone

### Recommended: run directly from Android Studio

1. On the Samsung phone, open `Settings`.
2. Go to `About phone` -> `Software information`.
3. Tap `Build number` 7 times until Developer options are enabled.
4. Go back to `Settings` -> `Developer options`.
5. Enable `USB debugging`.
6. Connect the phone to the computer with a USB cable.
7. If the phone asks for USB debugging authorization, tap `Allow`.
8. In Android Studio, wait until the Samsung device appears in the device selector.
9. Press `Run` for the `app` configuration.
10. Android Studio will build, install, and launch the app on the phone.

### If you build an APK first

1. Build the APK in Android Studio with `Build` -> `Build Bundle(s) / APK(s)` -> `Build APK(s)`.
2. Copy the APK to the Samsung phone.
3. Open the APK on the phone.
4. If Samsung blocks the install, allow `Install unknown apps` for the app you used to open the APK.
5. Confirm installation.

## First-Time Use On The Phone

1. Mount the phone firmly in the kart so it does not move during the session.
2. Keep the phone roughly aligned with the kart's forward direction.
3. Before driving, place the kart at a standstill.
4. Tap `Start`.
5. Keep the kart still for about 2 seconds while calibration runs.
6. Wait until the status changes from calibration to recording.
7. Drive the session.
8. Tap `Stop` after the run.
9. Open `View detected laps` to inspect lap times.
10. Open `Compare laps` to overlay two laps, inspect braking and cornering markers, read the delta graph, and review the text insights.

## Practical Tips For Better Data

- Use the same phone mount position every time.
- Start calibration only when the kart is fully stationary.
- Avoid touching or rotating the phone during the run.
- Longer sessions with multiple clean laps produce better lap detection.
- The app is designed for indoor use and does not require GPS.

## Notes

- No database is used. Data is kept in memory for the current app process.
- Lap detection is heuristic and intended as a working MVP baseline.
- The local environment used to generate this project did not include Java, Gradle, or Android SDK tools, so a real build was not executed here.

## Documentation

- Detailed project documentation: `docs/PROJEKTDOKUMENTATION.md`
