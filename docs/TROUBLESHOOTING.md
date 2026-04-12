<!-- generated-by: gsd-doc-writer -->
# Troubleshooting Guide

## Recording Issues

### Recording doesn't start
- **Check:** Are sensors available? App needs accelerometer and gyroscope
- **Fix:** Restart app, check if other sensor apps work

### Recording stops unexpectedly
- **Check:** Battery optimization enabled? → Follow battery settings guide
- **Check:** Phone memory low? → Clear old sessions, free up storage

### No coaching feedback after recording
- **Check:** Is analysis still running? Check notification
- **Wait:** Background analysis takes 30–60 seconds
- **Fix:** Open session details, tap "Reprocess session" if available

## Performance Issues

### App hangs when opening session list
- **Fix:** Delete old sessions or use track filter

### Stop button takes long time
- **Expected:** Should complete in <3 seconds
- **If longer:** Restart app; check logs

## Data Issues

### Accidentally deleted session
- **Solution:** Restore is only available via the UNDO action in the Snackbar that appears immediately after deleting a session in the session list. Once the Snackbar is dismissed there is no way to recover the session.

### Accidentally deleted track
- **Solution:** All sessions moved to Recently Deleted
- **Action:** Restore within 7-day window

## Getting Help

1. Export your data from the Session library screen using Export CSV or Export Backup
2. Report issue with exported data attached
3. Include device model and Android version
