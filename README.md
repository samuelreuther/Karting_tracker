# Karting Tracker

Android MVP for indoor karting analysis using accelerometer and gyroscope data only.

## What it does

- Records accelerometer and gyroscope data at `SENSOR_DELAY_FASTEST`
- Applies a simple low-pass filter
- Derives longitudinal and lateral acceleration from device axes
- Detects repeating lap boundaries with sliding-window similarity
- Stores sessions and laps in memory
- Lists detected laps with lap times
- Normalizes laps to 0-100 percent and overlays them with MPAndroidChart

## Device assumption

The current MVP assumes the phone is mounted flat with the top edge pointing forward.

- Device `Y` axis -> longitudinal acceleration
- Device `X` axis -> lateral acceleration

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

## Notes

- No database is used. Data is kept in memory for the current app process.
- Lap detection is heuristic and intended as a working MVP baseline.
- The local environment used to generate this project did not include Java, Gradle, or Android SDK tools, so a real build was not executed here.

## Documentation

- Detailed project documentation: `docs/PROJEKTDOKUMENTATION.md`
