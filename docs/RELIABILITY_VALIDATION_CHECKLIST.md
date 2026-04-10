# Reliability Validation Checklist (Real Device)

Use this checklist to verify recorder reliability and raw-session safety on a physical Android phone.

## Preconditions
- Build a debug APK with logcat access.
- Select a valid track.
- Confirm foreground notification permissions are granted.

## A) 3-minute simulated session
1. Run simulator for 3 minutes from the debug action.
2. Verify logs include:
   - entered RECORDING
   - first sample after RECORDING
   - heartbeat logs every few seconds
   - autosave success with file path + byte size
   - raw final save success with file path + byte size + sample count
   - processing start and processing end
3. Verify diagnostics panel shows non-empty raw path and raw status.

## B) 10-minute simulated session
1. Run simulator for 10 minutes.
2. Verify repeated heartbeat and autosave success logs.
3. Verify no FAILED/ABORTED state unless intentionally induced.

## C) 5-minute real pocket/background recording
1. Start recording on track, lock screen and keep phone in pocket.
2. Wait >= 5 minutes.
3. Verify service remains foreground and heartbeat logs continue.
4. Return to app and confirm state is still RECORDING with rising sample count.

## D) Return to app mid-recording
1. During active recording, open app from notification.
2. Verify UI state and diagnostics reflect active session (state, sample count, ages, watchdog active).

## E) Stop and verify raw persistence even if processing fails
1. Stop recording.
2. Confirm raw final save success log appears before processing start.
3. If processing fails, confirm:
   - state is FAILED (explicit),
   - raw file path remains present,
   - raw file is non-empty and reprocessable.

## F) Force processing failure and verify raw survives
1. Use a debug/instrumented build to throw after raw save and before final processed save.
2. Confirm logs show:
   - entered SAVING_RAW
   - raw final save success
   - processing start
   - processing end with failed state/reason
3. Confirm raw file still exists and can be loaded/reprocessed.

## G) Force sensor stall and verify watchdog reason
1. Start recording and inject/induce sensor callback freeze.
2. Confirm heartbeat stops advancing sample age and watchdog fires.
3. Confirm explicit watchdog stop reason is visible in logs and diagnostics panel.
