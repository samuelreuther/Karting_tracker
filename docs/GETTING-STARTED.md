<!-- generated-by: gsd-doc-writer -->
# Getting Started

This guide covers everything needed to clone, build, install, and run Karting Tracker on a
physical Android device for the first time.

---

## Prerequisites

### Development Machine

| Requirement | Details |
|---|---|
| Android Studio | Current stable release |
| JDK 21 | Required — see [Gradle/JDK Compatibility](#gradlejdk-compatibility) below |
| Android SDK | Installed through Android Studio (SDK Manager) |
| Android SDK Platform Tools | Required for device communication via ADB |

### Target Device

The app requires a physical Android device. An emulator can run the UI but cannot produce
realistic IMU telemetry for testing sensor-based features.

| Requirement | Details |
|---|---|
| Android version | 8.0 Oreo (API 26) or newer |
| Accelerometer | Required (`android.hardware.sensor.accelerometer`) |
| Gyroscope | Required (`android.hardware.sensor.gyroscope`) |
| USB cable | For initial sideload via Android Studio |

Devices missing either sensor cannot install the app from the Play Store. Most modern
smartphones include both sensors; budget devices or tablets sometimes omit the gyroscope.

---

## Gradle/JDK Compatibility

This project uses the Gradle wrapper pinned to **Gradle 8.14.3**. The Android Gradle Plugin
version is **8.5.2**.

If Gradle sync fails immediately with an error that only shows a bare Java version number
(e.g., `25.0.1`), the Gradle daemon is running under a JDK that is too new for this
wrapper/tooling combination.

**Fix: configure JDK 21 in Android Studio**

1. Open **Settings** (Windows/Linux) or **Preferences** (macOS).
2. Navigate to **Build, Execution, Deployment > Build Tools > Gradle**.
3. Set **Gradle JDK** to **JDK 21**.
4. Click **OK** and let Gradle re-sync.

**Command-line builds:** set `JAVA_HOME` to point to a JDK 21 installation before running
`./gradlew`.

The Gradle daemon is configured with `-Xmx2048m` heap (`gradle.properties`). If you see
out-of-memory errors during large builds, this value can be increased in `gradle.properties`.

Note: although source/target compatibility is set to Java 17 (`JavaVersion.VERSION_17`), the
Gradle daemon itself must run under JDK 21. JDK 17 is sufficient for compiling the Kotlin/Java
sources but may fail to launch the Gradle daemon with this wrapper version.

---

## Clone and Open in Android Studio

1. Clone the repository:

   ```bash
   git clone <repository-url>
   cd Karting_Tracker
   ```

2. Open Android Studio.
3. Choose **File > Open** and select the cloned repository root (the folder containing
   `settings.gradle.kts`).
4. Android Studio will detect the Gradle project and prompt to sync.

---

## First Build

1. Wait for Android Studio to finish downloading the Gradle wrapper distribution and resolving
   dependencies. This requires an active internet connection on first run.
2. Confirm that **Gradle JDK** is set to JDK 21 (see [Gradle/JDK Compatibility](#gradlejdk-compatibility))
   before sync completes — a mis-configured JDK causes sync to fail.
3. Once sync reports success, build the project via **Build > Make Project**.
4. A clean build produces the debug APK at `app/build/outputs/apk/debug/app-debug.apk`.

To run only the unit tests without a device:

```bash
./gradlew :app:testDebugUnitTest
```

---

## Install on Device

### Option A: Direct Install via Android Studio (Recommended)

This is the fastest path and works for all supported Android devices.

1. Enable developer mode and USB debugging on the device (see below for Samsung-specific steps).
2. Connect the device to the development machine via USB.
3. Accept the **Allow USB debugging** authorization prompt on the device screen.
4. In Android Studio, select the device from the target device dropdown (top toolbar).
5. Press **Run** (green play button) or use **Run > Run 'app'**.

Android Studio builds the debug APK, installs it, and launches the app automatically.

### Option B: Manual APK Install

Use this if you want to install the app on a device without a USB connection to the development
machine (e.g., copying the APK to the phone via file transfer).

1. Build the APK: **Build > Build Bundle(s) / APK(s) > Build APK(s)**.
2. Locate the APK at `app/build/outputs/apk/debug/app-debug.apk`.
3. Transfer the APK to the target phone (via USB file transfer, cloud storage, or email).
4. Open the APK file on the phone using a file manager.
5. If the system blocks installation, go to **Settings > Apps > Special App Access > Install
   Unknown Apps** and grant permission to the app used to open the file (e.g., the file manager).
6. Confirm installation.

---

## Samsung Developer Mode and USB Debugging

These steps apply to Samsung Galaxy devices running One UI. The exact menu names may differ
slightly by model and Android version.

1. Open **Settings**.
2. Tap **About phone**, then **Software information**.
3. Tap **Build number** seven times in rapid succession. You will see a toast confirming that
   developer mode is enabled.
4. Go back to **Settings** and open **Developer options** (now visible near the bottom of the
   Settings list or under **General management**).
5. Enable the **USB debugging** toggle.
6. Connect the phone to the development machine via USB.
7. On the phone, accept the **Allow USB debugging?** dialog (check "Always allow from this
   computer" to avoid repeating this step).

The device now appears as a target in Android Studio's device dropdown.

---

## First Use Flow

After the app launches on the device for the first time:

1. **Allow notifications** — On Android 13 and newer, the app prompts for the `POST_NOTIFICATIONS`
   permission. Grant it. Without this permission the foreground recording service cannot display
   its persistent notification, which reduces recording reliability.

2. **Select or create a track** — Tap the track dropdown on the main screen. Choose an existing
   track from the list or select **+ Add new track** at the bottom of the dropdown to create one.
   Recording cannot start until a track is selected.

3. **Position the phone** — Place the phone in the kart mount or in a consistent pocket position.
   Keep phone orientation the same between sessions on the same track for the best chart accuracy.

4. **Press Start** — A 10-second pre-start countdown begins. The screen shows a "put phone in
   pocket now" prompt and a clear countdown. Recording has not started yet at this point.

5. **Calibration phase** — After the countdown, the app enters a 2-second calibration phase.
   The kart must be fully stationary during this window. The app averages accelerometer samples
   to estimate the gravity vector and derive the driving plane.

6. **Drive the session** — The foreground notification displays the current track, elapsed time,
   and sample count. The screen can be locked or the app can be backgrounded while recording
   continues.

7. **Stop recording** — Press **Stop** in the app or tap the stop action in the persistent
   notification. The stop sequence runs through several states: `Stopping recording…` →
   `Saving raw session…` → `Processing laps…` → `Finalizing session…`. Background analysis
   takes 30–60 seconds for a typical session.

8. **Review results** — After analysis completes, tap **View detected laps** to inspect detected
   laps, or **Compare laps** to open the comparison screen.

---

## Battery Optimization Settings

OEM battery policies (especially on Samsung devices) can suspend the foreground service or
restrict sensor access, which degrades recording reliability.

For consistent results, disable battery optimization for Karting Tracker before your first
session. Full instructions are in [docs/USER_GUIDE_RELIABILITY.md](USER_GUIDE_RELIABILITY.md).

**Quick steps for Samsung devices:**

1. Open **Settings > Apps > Karting Tracker > Battery**.
2. Select **Unrestricted**.
3. Toggle OFF "Put app to sleep".

The app's health watchdog will log a `BATTERY_OPTIMIZATION` issue if it detects that battery
optimization may interfere with the recording session.

---

## Common Setup Issues

### Gradle sync fails with a bare Java version number (e.g., `25.0.1`)

The Gradle daemon is running under a JDK that is too new. Set the Gradle JDK to JDK 21 in
**Settings > Build, Execution, Deployment > Build Tools > Gradle > Gradle JDK** and re-sync.

### Gradle sync fails with "Could not resolve dependencies"

The first sync requires internet access to download the Gradle distribution and Maven
dependencies. Ensure the development machine is online during the initial sync.

### Device not appearing in Android Studio's device dropdown

- Confirm USB debugging is enabled on the device.
- Accept the authorization prompt on the device if it has not appeared yet.
- Try a different USB cable or port — some cables are charge-only.
- Run `adb devices` in a terminal to verify the device is recognized by the platform tools.

### App crashes immediately on first launch

Confirm the device meets the minimum API 26 requirement and has both an accelerometer and a
gyroscope. Open **Logcat** in Android Studio (filter by package `com.kartingtracker`) to read
the crash message.

### No laps detected after first session

The first session on a new track has no learned track profile yet, so lap detection runs on
signal heuristics alone. Confidence improves after 3–5 sessions once a `TrackProfile` is
established. Keep phone placement consistent between sessions.

---

## Next Steps

- **Development workflow, build commands, and code style:** see `docs/DEVELOPMENT.md`
- **Test framework, running tests, and coverage:** see `docs/TESTING.md`
- **Build configuration and runtime constants:** see `docs/CONFIGURATION.md`
- **Recording issues and troubleshooting:** see `docs/TROUBLESHOOTING.md`
- **Battery settings and OEM restrictions:** see `docs/USER_GUIDE_RELIABILITY.md`
