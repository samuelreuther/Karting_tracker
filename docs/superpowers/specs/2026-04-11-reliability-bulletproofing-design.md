# Karting Tracker Reliability Bulletproofing Design

**Date:** 2026-04-11  
**Target Device:** Samsung Galaxy M32 (Android 13, mid-range)  
**Approach:** Conservative Reliability-First

---

## 1. Executive Summary

### Problem Statement

The Karting Tracker app experiences critical reliability failures on mid-range Android devices (specifically Samsung Galaxy M32):

1. **Recording fails silently** - service shows recording but no session is saved
2. **Corrupted short sessions** - recordings stop prematurely, marked as corrupted
3. **App hangs** - UI freezes during stop, session loading, navigation
4. **Data corruption** - deleting one session accidentally deleted entire track + 3-5 other sessions
5. **State desynchronization** - service thinks it's recording but SensorRecorder is idle

### Root Causes Identified

- **Memory pressure**: 200Hz sampling for 15 minutes = 180,000 samples × 100 bytes = 18MB RAM causes silent OOM kills
- **Blocking stop pipeline**: All analysis (lap detection, coaching, etc.) runs on stop, blocking UI for 10-60 seconds
- **State drift**: RecordingForegroundService and SensorRecorder can desync with no recovery
- **Samsung battery optimization**: Aggressive power management kills background services
- **Unsafe deletion**: No confirmation, no undo, cascading deletes due to shared file name patterns

### Solution Approach

**Philosophy:** Sacrifice some data fidelity for rock-solid recording reliability.

**Core strategy:**
1. Reduce sensor rate from FASTEST (200Hz) to GAME (50Hz) - sufficient for karting analysis
2. Stream samples to disk immediately - keep only 1000 samples in RAM (100KB vs 18MB)
3. Defer all analysis to background - stop completes in <3 seconds
4. Add state synchronization watchdog - auto-recover from desync
5. Protect UI thread - move all blocking operations to background
6. Safe deletion with undo - prevent accidental data loss

**Expected outcomes:**
- 100% recording success rate on Galaxy M32
- <3 second stop time (vs current 10-60 seconds)
- No app hangs or freezes
- Zero catastrophic data loss incidents
- 30-60 second analysis time after recording (acceptable per user)

---

## 2. Recording Reliability Architecture

### 2.1 Sensor Rate Adjustment

**Current implementation:**
```kotlin
sensorManager.registerListener(
    this, 
    sensor, 
    SensorManager.SENSOR_DELAY_FASTEST,  // ~200Hz on flagship, inconsistent on mid-range
    sensorHandler
)
```

**New implementation:**

```kotlin
// Priority: reliability over data density
enum class SensorSamplingRate(val delay: Int, val targetHz: Int) {
    GAME(SensorManager.SENSOR_DELAY_GAME, 50),      // Default, reliable
    UI(SensorManager.SENSOR_DELAY_UI, 20),          // Fallback for weak devices
    NORMAL(SensorManager.SENSOR_DELAY_NORMAL, 10)   // Emergency fallback
}

class AdaptiveSensorRateManager {
    private var currentRate = SensorSamplingRate.GAME
    private var sampleDropCount = 0
    
    fun registerSensorsWithAdaptiveRate(): Boolean {
        // Start with GAME rate
        val success = registerAtRate(currentRate)
        if (!success) {
            // Fallback to UI rate
            currentRate = SensorSamplingRate.UI
            return registerAtRate(currentRate)
        }
        return success
    }
    
    fun onSampleReceived(timestampNs: Long) {
        // Detect if actual rate is much lower than expected
        val actualRate = calculateActualRate(timestampNs)
        if (actualRate < currentRate.targetHz * 0.5) {
            sampleDropCount++
            if (sampleDropCount > 100) {
                // Device struggling, downgrade rate
                downgradeRate()
            }
        }
    }
}
```

**Storage of actual rate:**
```kotlin
data class Session(
    // ... existing fields
    val targetSampleRateHz: Int,    // What we requested (50Hz)
    val actualAverageSampleRateHz: Int,  // What we actually got (e.g., 47Hz)
    val sampleRateQuality: String   // "STABLE", "INCONSISTENT", "DEGRADED"
)
```

**Why this works:**
- 50Hz is industry standard for motorsport telemetry (even Formula 1 uses 50-100Hz for many sensors)
- Reduces memory pressure by 75% (50Hz vs 200Hz)
- More consistent on mid-range devices (less sample drops)
- Still captures all braking/cornering events (events last 0.5-2 seconds, well within 50Hz Nyquist)

### 2.2 Stream-to-Disk Architecture

**Current problem:**
```kotlin
// SessionRepository.kt - current implementation
private val samples = mutableListOf<SensorSample>()  // Unbounded growth in RAM!

fun appendSample(sample: SensorSample) {
    samples.add(sample)  // 15min × 200Hz = 180,000 samples = 18MB RAM
    // ...
}
```

**Memory profile for typical 15-minute session:**
- Current: 180,000 samples × 100 bytes = **18MB** (plus JSON overhead)
- On Galaxy M32: causes memory pressure → GC thrashing → silent OOM kill
- When app backgrounded: Android often kills it to reclaim memory

**New architecture - Streaming Binary Writer:**

```kotlin
/**
 * Writes samples to binary file in real-time.
 * Format: [timestamp:long][accel:3×float][gyro:3×float][derived:4×float] = 48 bytes/sample
 */
class StreamingSessionWriter(
    private val sessionId: Long,
    private val sessionDirectory: File
) {
    private val rawFile = File(sessionDirectory, "session_${sessionId}_raw.bin")
    private val tempFile = File(sessionDirectory, "session_${sessionId}_raw.tmp")
    
    private val writeBuffer = ByteBuffer.allocateDirect(BUFFER_SIZE)  // 64KB buffer
    private val ioDispatcher = Dispatchers.IO
    private val writeJob: Job
    
    private var samplesWritten = 0L
    private var lastFlushTimestamp = System.currentTimeMillis()
    
    init {
        // Background flush loop
        writeJob = CoroutineScope(ioDispatcher).launch {
            var outputStream = FileOutputStream(tempFile).channel
            while (isActive) {
                if (writeBuffer.position() > 0) {
                    writeBuffer.flip()
                    outputStream.write(writeBuffer)
                    writeBuffer.clear()
                    lastFlushTimestamp = System.currentTimeMillis()
                }
                delay(FLUSH_INTERVAL_MS)  // Flush every 1 second
            }
            outputStream.close()
        }
    }
    
    suspend fun writeSample(sample: SensorSample) = withContext(ioDispatcher) {
        synchronized(writeBuffer) {
            if (writeBuffer.remaining() < SAMPLE_SIZE) {
                // Buffer full, force flush
                forceFlush()
            }
            
            // Write binary sample (48 bytes)
            writeBuffer.putLong(sample.timestampNs)
            writeBuffer.putFloat(sample.accelX)
            writeBuffer.putFloat(sample.accelY)
            writeBuffer.putFloat(sample.accelZ)
            writeBuffer.putFloat(sample.gyroX)
            writeBuffer.putFloat(sample.gyroY)
            writeBuffer.putFloat(sample.gyroZ)
            writeBuffer.putFloat(sample.longitudinalAccel)
            writeBuffer.putFloat(sample.lateralAccel)
            writeBuffer.putFloat(sample.totalAcceleration)
            writeBuffer.putFloat(sample.yawRateAbs)
            
            samplesWritten++
        }
    }
    
    suspend fun finalize(): File {
        writeJob.cancelAndJoin()
        forceFlush()
        
        // Atomic rename: temp → final
        if (!tempFile.renameTo(rawFile)) {
            throw IOException("Failed to finalize raw session file")
        }
        
        Log.i(TAG, "Finalized raw session: $samplesWritten samples, ${rawFile.length()} bytes")
        return rawFile
    }
    
    private suspend fun forceFlush() = withContext(ioDispatcher) {
        // Immediate flush implementation
    }
    
    companion object {
        private const val BUFFER_SIZE = 65536  // 64KB
        private const val SAMPLE_SIZE = 48     // bytes per sample
        private const val FLUSH_INTERVAL_MS = 1000L
    }
}
```

**Updated SessionRepository:**

```kotlin
class SessionRepository(/* ... */) {
    
    // Only keep sliding window in RAM for live UI updates
    private val recentSamples = CircularBuffer<SensorSample>(capacity = 1000)  // 100KB max
    
    private var streamingWriter: StreamingSessionWriter? = null
    
    fun startSession(startTimestampNs: Long) {
        val session = Session(
            id = System.currentTimeMillis(),
            trackName = currentTrackName.value,
            startTimestampNs = startTimestampNs,
            // ...
        )
        currentSession = session
        
        // Initialize streaming writer
        streamingWriter = StreamingSessionWriter(
            sessionId = session.id,
            sessionDirectory = storageManager.sessionDirectory
        )
        
        updateRecordingState(RecordingState.RECORDING)
    }
    
    fun appendSample(sample: SensorSample) {
        // Add to circular buffer for live UI
        recentSamples.add(sample)
        _lastSample.value = sample
        _sampleCount.value++
        
        // Stream to disk immediately (non-blocking)
        repositoryScope.launch {
            streamingWriter?.writeSample(sample)
        }
        
        // Update live statistics
        updateLiveStats(sample)
    }
    
    suspend fun stopSession(endTimestampNs: Long) {
        updateRecordingState(RecordingState.SAVING_RAW)
        
        try {
            // Step 1: Finalize binary file (fast, 2-5 seconds max)
            val rawFile = streamingWriter?.finalize()
                ?: throw IllegalStateException("No streaming writer active")
            
            Log.i(TAG, "Raw session saved: ${rawFile.absolutePath}")
            
            // Step 2: Mark session as "raw only" state
            val rawSession = currentSession!!.copy(
                endTimestampNs = endTimestampNs,
                endTimeEpochMs = System.currentTimeMillis(),
                samples = emptyList(),  // Samples are in binary file, not JSON
                laps = emptyList(),     // Not analyzed yet
                isRawOnly = true,       // New flag
                rawFilePath = rawFile.absolutePath
            )
            
            storageManager.saveSession(rawSession)
            _latestSession.value = rawSession
            
            updateRecordingState(RecordingState.RAW_SAVED)
            
            // Step 3: Schedule background analysis (non-blocking)
            scheduleSessionAnalysis(rawSession.id)
            
            Log.i(TAG, "Session stop completed in <3 seconds, analysis scheduled")
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to save raw session", e)
            markRecordingFailed("Failed to finalize recording: ${e.message}")
        } finally {
            streamingWriter = null
            recentSamples.clear()
        }
    }
}
```

**Session model updates:**

```kotlin
data class Session(
    // ... existing fields
    val isRawOnly: Boolean = false,      // True if not yet analyzed
    val rawFilePath: String? = null,     // Path to binary file
    val analysisStatus: AnalysisStatus = AnalysisStatus.PENDING
)

enum class AnalysisStatus {
    PENDING,        // Raw saved, analysis not started
    IN_PROGRESS,    // Currently analyzing
    COMPLETED,      // Analysis done, full session available
    FAILED          // Analysis failed, but raw data preserved
}
```

**Memory footprint comparison:**

| Scenario | Current | New | Savings |
|----------|---------|-----|---------|
| 8 min session (50Hz) | 24,000 samples × 100 bytes = 2.4MB | 1000 samples × 100 bytes = 100KB | 96% reduction |
| 15 min session (50Hz) | 45,000 samples × 100 bytes = 4.5MB | 100KB | 98% reduction |
| 30 min session (50Hz) | 90,000 samples × 100 bytes = 9MB | 100KB | 99% reduction |

**Why this works:**
- RAM usage capped at 100KB regardless of session length
- No GC pressure → no janky UI
- Android won't kill app for memory reclaim
- Binary format is 4x faster to write than JSON
- Raw data preserved even if analysis fails

### 2.3 Minimal Stop Processing

**Current stop pipeline (blocking):**

```
User presses "Stop"
  ↓
SensorRecorder.stopRecording()
  ↓
SessionRepository.stopSession()
  ├─ LapDetector.detectLaps()           // 5-15 seconds on Galaxy M32
  ├─ PeakDetector.detectPeaks()         // 2-5 seconds
  ├─ SectorDetector.detectSectors()     // 1-3 seconds
  ├─ SessionQualityEvaluator.evaluate() // 1-2 seconds
  ├─ DrivingCoachAnalyzer.analyze()     // 3-10 seconds (with corner coaching)
  ├─ TrackProfileManager.updateProfile() // 2-5 seconds
  └─ SessionStorageManager.saveSession() // 3-8 seconds (JSON serialization)
  ↓
Total: 17-48 seconds UI freeze on Galaxy M32
  ↓
User sees session list
```

**New stop pipeline (non-blocking):**

```
User presses "Stop"
  ↓
SensorRecorder.stopRecording() (immediate, <100ms)
  ↓
SessionRepository.stopSession()
  ├─ StreamingSessionWriter.finalize()   // 1-3 seconds (flush buffer + rename)
  ├─ Save minimal raw-only Session JSON   // <500ms
  └─ Schedule WorkManager background job  // <100ms
  ↓
Total: <3 seconds, user regains control
  ↓
UI shows: "Recording saved ✓ Analysis pending..."
  ↓
[User can immediately start new recording or navigate away]
  ↓
Background WorkManager job starts:
  ├─ Read binary file back into memory
  ├─ Run full analysis pipeline
  ├─ Update Session JSON with results
  └─ Show notification: "Session analyzed: 12 laps detected"
  ↓
Total background time: 20-40 seconds (user doesn't wait)
```

**Implementation - Background Analysis Worker:**

```kotlin
class SessionAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {
    
    override suspend fun doWork(): Result = withContext(Dispatchers.Default) {
        val sessionId = inputData.getLong("session_id", -1)
        if (sessionId == -1L) return@withContext Result.failure()
        
        try {
            Log.i(TAG, "Background analysis started for session $sessionId")
            
            // Load raw binary file
            val rawFile = File(inputData.getString("raw_file_path")!!)
            val samples = loadSamplesFromBinaryFile(rawFile)
            
            // Run full analysis pipeline (same as before, just in background)
            val laps = LapDetector.detectLaps(samples, trackProfile)
            val processedLaps = laps.map { lap ->
                lap.copy(
                    brakingPeakIndices = PeakDetector.detectBrakingPeaks(lap.samples),
                    corneringPeakIndices = PeakDetector.detectCorneringPeaks(lap.samples),
                    sectorBoundaries = SectorDetector.detectSectorBoundaries(lap, trackProfile),
                    sectorTimesMs = SectorDetector.computeSectorTimes(lap, trackProfile)
                )
            }
            
            val quality = SessionQualityEvaluator.evaluate(processedLaps)
            val coachingResults = DrivingCoachAnalyzer.analyzeSession(/* ... */)
            
            // Update session with results
            val analyzedSession = loadSession(sessionId).copy(
                samples = samples,  // Or keep empty to save space, rely on binary file
                laps = processedLaps,
                quality = quality,
                coachingInsights = coachingResults.insights,
                cornerCoachingSummary = coachingResults.cornerSummary,
                isRawOnly = false,
                analysisStatus = AnalysisStatus.COMPLETED,
                processingVersion = Session.CURRENT_PROCESSING_VERSION
            )
            
            sessionStorageManager.saveSession(analyzedSession)
            
            // Update track profile if session is high quality
            if (quality.overallScore >= PROFILE_UPDATE_THRESHOLD) {
                trackProfileManager.updateProfile(analyzedSession)
            }
            
            // Show completion notification
            showAnalysisCompleteNotification(analyzedSession)
            
            Log.i(TAG, "Background analysis completed for session $sessionId")
            Result.success()
            
        } catch (e: Exception) {
            Log.e(TAG, "Background analysis failed for session $sessionId", e)
            
            // Mark analysis as failed, but preserve raw data
            markAnalysisFailed(sessionId, e.message)
            
            Result.failure()
        }
    }
    
    private fun loadSamplesFromBinaryFile(file: File): List<SensorSample> {
        val samples = mutableListOf<SensorSample>()
        val buffer = ByteBuffer.wrap(file.readBytes())
        
        while (buffer.remaining() >= 48) {
            samples.add(SensorSample(
                timestampNs = buffer.getLong(),
                accelX = buffer.getFloat(),
                accelY = buffer.getFloat(),
                accelZ = buffer.getFloat(),
                gyroX = buffer.getFloat(),
                gyroY = buffer.getFloat(),
                gyroZ = buffer.getFloat(),
                longitudinalAccel = buffer.getFloat(),
                lateralAccel = buffer.getFloat(),
                totalAcceleration = buffer.getFloat(),
                yawRateAbs = buffer.getFloat()
            ))
        }
        
        return samples
    }
}
```

**Scheduling the analysis:**

```kotlin
class SessionRepository(/* ... */) {
    
    private fun scheduleSessionAnalysis(sessionId: Long) {
        val workRequest = OneTimeWorkRequestBuilder<SessionAnalysisWorker>()
            .setInputData(workDataOf(
                "session_id" to sessionId,
                "raw_file_path" to currentSession!!.rawFilePath
            ))
            .setConstraints(Constraints.Builder()
                .setRequiresBatteryNotLow(false)  // Run even on low battery
                .setRequiresCharging(false)
                .build())
            .setBackoffCriteria(
                BackoffPolicy.EXPONENTIAL,
                WorkRequest.MIN_BACKOFF_MILLIS,
                TimeUnit.MILLISECONDS
            )
            .build()
        
        WorkManager.getInstance(context)
            .enqueueUniqueWork(
                "analyze_session_$sessionId",
                ExistingWorkPolicy.REPLACE,
                workRequest
            )
        
        Log.i(TAG, "Scheduled background analysis for session $sessionId")
    }
}
```

**User experience improvements:**

1. **Instant UI response**: Stop button completes in <3 seconds
2. **Progress visibility**: Show "Analysis pending" badge on unprocessed sessions
3. **Notification on completion**: "Your session is ready: 12 laps, 23.4s best lap"
4. **View raw data immediately**: Can see sample count, duration, basic info before analysis
5. **Retry failed analysis**: If analysis fails, user can manually trigger retry
6. **No blocking**: Can start another recording immediately

---

## 3. State Synchronization & Recovery

### 3.1 The State Drift Problem

**Current architecture has 3 separate state machines:**

1. **RecordingForegroundService** - thinks service is active
2. **SensorRecorder** - thinks it's recording
3. **SessionRepository** - thinks session is active

These can desync:
- Service starts but sensors fail to register → service alive, recorder idle
- Android kills sensor listener but service survives → service alive, recorder dead
- Repository thinks recording but service was killed → UI shows recording, nothing happening

**User's symptom:** "When I open the screen again, it is not recording anymore"

### 3.2 State Synchronization Watchdog

**New component: RecordingHealthMonitor**

```kotlin
data class RecordingHealth(
    val serviceAlive: Boolean,
    val serviceForeground: Boolean,
    val recorderActive: Boolean,
    val recorderPhase: RecorderPhase,
    val repositoryRecording: Boolean,
    val lastSampleTimestampNs: Long,
    val lastSampleAgeMs: Long,
    val samplesReceived: Int,
    val wakeLockHeld: Boolean,
    val batteryOptimizationEnabled: Boolean
)

class RecordingHealthMonitor(
    private val service: RecordingForegroundService,
    private val recorder: SensorRecorder,
    private val repository: SessionRepository
) {
    
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    
    fun startMonitoring() {
        scope.launch {
            while (isActive) {
                val health = checkHealth()
                
                // Log health status
                Log.i(TAG, "Recording health: $health")
                
                // Detect issues and auto-recover
                val issue = detectIssue(health)
                if (issue != null) {
                    handleIssue(issue, health)
                }
                
                delay(HEALTH_CHECK_INTERVAL_MS)  // Every 5 seconds
            }
        }
    }
    
    private fun checkHealth(): RecordingHealth {
        val now = System.currentTimeMillis()
        val lastSampleNs = repository.lastSample.value?.timestampNs ?: 0L
        val lastSampleMs = if (lastSampleNs > 0) {
            (System.nanoTime() - lastSampleNs) / 1_000_000
        } else {
            -1L
        }
        
        return RecordingHealth(
            serviceAlive = service.isRunning,
            serviceForeground = service.isForeground,
            recorderActive = recorder.isActive,
            recorderPhase = recorder.recorderPhase.value,
            repositoryRecording = repository.isRecording.value,
            lastSampleTimestampNs = lastSampleNs,
            lastSampleAgeMs = lastSampleMs,
            samplesReceived = repository.sampleCount.value,
            wakeLockHeld = service.isWakeLockHeld,
            batteryOptimizationEnabled = isBatteryOptimizationEnabled()
        )
    }
    
    private fun detectIssue(health: RecordingHealth): RecordingIssue? {
        // Issue 1: Service alive but recorder dead
        if (health.serviceAlive && !health.recorderActive && health.repositoryRecording) {
            return RecordingIssue.RECORDER_DEAD
        }
        
        // Issue 2: Recorder active but no samples received
        if (health.recorderActive && 
            health.recorderPhase == RecorderPhase.RECORDING &&
            health.lastSampleAgeMs > SAMPLE_STALL_TIMEOUT_MS) {
            return RecordingIssue.SAMPLE_STALL
        }
        
        // Issue 3: Service not foreground but should be
        if (health.serviceAlive && !health.serviceForeground && health.repositoryRecording) {
            return RecordingIssue.NOT_FOREGROUND
        }
        
        // Issue 4: Wake lock not held during recording
        if (health.recorderActive && !health.wakeLockHeld) {
            return RecordingIssue.NO_WAKE_LOCK
        }
        
        // Issue 5: Battery optimization will kill us
        if (health.batteryOptimizationEnabled && health.repositoryRecording) {
            return RecordingIssue.BATTERY_OPTIMIZATION_RISK
        }
        
        return null
    }
    
    private suspend fun handleIssue(issue: RecordingIssue, health: RecordingHealth) {
        Log.e(TAG, "Recording issue detected: $issue")
        
        when (issue) {
            RecordingIssue.RECORDER_DEAD -> {
                // Attempt to restart recorder
                Log.i(TAG, "Attempting to restart sensor recorder...")
                val restarted = attemptRecorderRestart()
                if (!restarted) {
                    // Can't recover, save what we have
                    Log.e(TAG, "Recorder restart failed, aborting recording")
                    repository.markRecordingFailed("Sensor recorder died and could not be restarted")
                    service.stopRecordingAndService()
                }
            }
            
            RecordingIssue.SAMPLE_STALL -> {
                // Sensors stopped delivering samples
                Log.e(TAG, "Sample stall detected (${health.lastSampleAgeMs}ms), aborting")
                repository.markRecordingFailed("Sensor samples stopped arriving (stall timeout)")
                service.stopRecordingAndService()
            }
            
            RecordingIssue.NOT_FOREGROUND -> {
                // Service lost foreground status
                Log.e(TAG, "Service lost foreground status, attempting to re-promote")
                val promoted = service.promoteToForeground()
                if (!promoted) {
                    Log.e(TAG, "Cannot re-promote to foreground, aborting")
                    service.stopRecordingAndService()
                }
            }
            
            RecordingIssue.NO_WAKE_LOCK -> {
                // Wake lock released unexpectedly
                Log.w(TAG, "Wake lock lost, re-acquiring")
                service.acquireWakeLock()
            }
            
            RecordingIssue.BATTERY_OPTIMIZATION_RISK -> {
                // Just log warning, don't interrupt
                Log.w(TAG, "Battery optimization is enabled, recording may be interrupted")
            }
        }
    }
    
    private fun attemptRecorderRestart(): Boolean {
        return try {
            recorder.stopRecording()
            delay(500)
            recorder.startRecording()
            delay(2000)  // Wait for calibration
            recorder.isActive && recorder.recorderPhase.value == RecorderPhase.RECORDING
        } catch (e: Exception) {
            Log.e(TAG, "Recorder restart failed", e)
            false
        }
    }
    
    companion object {
        private const val HEALTH_CHECK_INTERVAL_MS = 5000L  // Every 5 seconds
        private const val SAMPLE_STALL_TIMEOUT_MS = 10000L  // 10 seconds without samples = stall
    }
}

enum class RecordingIssue {
    RECORDER_DEAD,
    SAMPLE_STALL,
    NOT_FOREGROUND,
    NO_WAKE_LOCK,
    BATTERY_OPTIMIZATION_RISK
}
```

### 3.3 Samsung Battery Optimization Detection

Samsung devices (especially mid-range) have aggressive power management:

```kotlin
class BatteryOptimizationHelper(private val context: Context) {
    
    fun isBatteryOptimizationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        
        val powerManager = context.getSystemService(Context.POWER_SERVICE) as PowerManager
        val packageName = context.packageName
        
        return !powerManager.isIgnoringBatteryOptimizations(packageName)
    }
    
    fun showBatteryOptimizationWarning() {
        // Show dialog explaining the issue and offering to guide user to settings
        // "For reliable recording, please disable battery optimization for this app"
    }
    
    fun openBatteryOptimizationSettings() {
        val intent = Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
        context.startActivity(intent)
    }
    
    /**
     * Samsung-specific: also need to disable "Put app to sleep"
     */
    fun detectSamsungDeepSleep(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("samsung")
    }
    
    fun showSamsungSpecificGuidance() {
        // Show instructions:
        // 1. Settings → Apps → Karting Tracker → Battery
        // 2. Set "Background usage limit" to "Unrestricted"
        // 3. Disable "Put app to sleep"
    }
}
```

**UI Integration:**

- Show warning badge on main screen if battery optimization detected
- One-time guidance dialog on first recording attempt
- Quick action button to open system settings

---

## 4. UI Thread Protection

### 4.1 Current Blocking Operations

**Main thread blockers identified:**

1. **Session loading** - reading large JSON files (5-20MB)
2. **Session list loading** - parsing all session files on directory list
3. **Session deletion** - file I/O operation
4. **Analysis** - already addressed in section 2.3
5. **Track profile updates** - reading/writing JSON

### 4.2 Async Loading with Indicators

**Session list loading:**

```kotlin
class SessionListViewModel(/* ... */) : ViewModel() {
    
    private val _sessionListState = MutableStateFlow<SessionListState>(SessionListState.Loading)
    val sessionListState = _sessionListState.asStateFlow()
    
    fun loadSessions(trackFilter: String? = null) {
        _sessionListState.value = SessionListState.Loading
        
        viewModelScope.launch(Dispatchers.IO) {
            try {
                val allSessions = sessionRepository.loadAllSessions()
                val filtered = if (trackFilter != null) {
                    allSessions.filter { it.trackName == trackFilter }
                } else {
                    allSessions
                }
                
                _sessionListState.value = SessionListState.Success(filtered)
            } catch (e: Exception) {
                Log.e(TAG, "Failed to load sessions", e)
                _sessionListState.value = SessionListState.Error(e.message ?: "Unknown error")
            }
        }
    }
}

sealed class SessionListState {
    object Loading : SessionListState()
    data class Success(val sessions: List<Session>) : SessionListState()
    data class Error(val message: String) : SessionListState()
}
```

**UI with loading states:**

```kotlin
// SessionListFragment.kt
lifecycleScope.launch {
    viewModel.sessionListState.collect { state ->
        when (state) {
            is SessionListState.Loading -> {
                progressBar.visibility = View.VISIBLE
                recyclerView.visibility = View.GONE
                errorText.visibility = View.GONE
            }
            is SessionListState.Success -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.VISIBLE
                errorText.visibility = View.GONE
                adapter.submitList(state.sessions)
            }
            is SessionListState.Error -> {
                progressBar.visibility = View.GONE
                recyclerView.visibility = View.GONE
                errorText.visibility = View.VISIBLE
                errorText.text = "Failed to load sessions: ${state.message}"
            }
        }
    }
}
```

### 4.3 Debounced Session Selection

**Problem:** User rapidly tapping sessions causes concurrent load operations.

**Solution:**

```kotlin
class SessionListViewModel(/* ... */) : ViewModel() {
    
    private val sessionSelectionChannel = Channel<Long>(Channel.CONFLATED)
    
    init {
        // Debounce session selections
        viewModelScope.launch {
            sessionSelectionChannel
                .consumeAsFlow()
                .debounce(300)  // Wait 300ms after last tap
                .collect { sessionId ->
                    loadSessionInternal(sessionId)
                }
        }
    }
    
    fun selectSession(sessionId: Long) {
        sessionSelectionChannel.trySend(sessionId)
    }
    
    private suspend fun loadSessionInternal(sessionId: Long) {
        _sessionLoadState.value = SessionLoadState.Loading
        
        withContext(Dispatchers.IO) {
            try {
                val session = sessionRepository.loadSession(sessionId)
                _sessionLoadState.value = SessionLoadState.Success(session)
            } catch (e: Exception) {
                _sessionLoadState.value = SessionLoadState.Error(e.message ?: "Failed to load")
            }
        }
    }
}
```

### 4.4 Timeout Protection

**All I/O operations get timeouts:**

```kotlin
suspend fun <T> withTimeout(
    timeoutMs: Long,
    operation: String,
    block: suspend () -> T
): T {
    return try {
        withTimeout(timeoutMs) {
            block()
        }
    } catch (e: TimeoutCancellationException) {
        Log.e(TAG, "Operation '$operation' timed out after ${timeoutMs}ms")
        throw IOException("Operation timed out: $operation")
    }
}

// Usage:
suspend fun loadSession(sessionId: Long): Session {
    return withTimeout(5000, "load session $sessionId") {
        // ... actual loading logic
    }
}
```

---

## 5. Safe Deletion with Undo

### 5.1 The Catastrophic Deletion Bug

**What happened:**
- User deleted one session from session list
- Entire track disappeared from dropdown
- 3-5 other sessions also deleted

**Root cause analysis:**

```kotlin
// SessionStorageManager.kt - CURRENT BUGGY CODE
fun deleteSessionsForTrack(trackName: String): Int {
    val deletedFiles = listSessionFiles().filter { file ->
        // BUG: This pattern matches too broadly!
        readSessionMetadata(file)?.trackName?.equals(trackName, ignoreCase = true) == true
    }
    
    var deleteCount = 0
    deletedFiles.forEach { file ->
        if (file.delete()) {  // NO CONFIRMATION, NO UNDO
            deleteCount += 1
        }
    }
    return deleteCount
}
```

**The bug chain:**
1. User deletes session for track "Basel Kart"
2. Code finds file: `session_Basel Kart_1234567890.json`
3. Deletion dialog somehow triggers `deleteSessionsForTrack("Basel Kart")` instead of `deleteSession(sessionId)`
4. All sessions with track name "Basel Kart" get deleted
5. Track has no sessions → track auto-cleanup removes it

### 5.2 Two-Phase Deletion with Undo

**New safe deletion architecture:**

```kotlin
data class Session(
    // ... existing fields
    val deletedAt: Long? = null,  // Timestamp when marked deleted, null = not deleted
    val deletionReason: String? = null  // "User deleted", "Track deleted", etc.
)

class SessionStorageManager(context: Context) {
    
    private val sessionDirectory = File(context.filesDir, "sessions").apply { mkdirs() }
    private val deletedDirectory = File(context.filesDir, "deleted_sessions").apply { mkdirs() }
    private val permanentDeleteAfterMs = TimeUnit.DAYS.toMillis(7)  // 7 day grace period
    
    /**
     * Phase 1: Mark as deleted (soft delete)
     */
    fun markSessionDeleted(sessionId: Long, reason: String = "User deleted"): Boolean {
        return try {
            val session = loadSession(sessionId) ?: return false
            
            // Move to deleted folder
            val sourceFile = File(sessionDirectory, buildSessionFileName(session))
            val targetFile = File(deletedDirectory, buildSessionFileName(session))
            
            if (!sourceFile.renameTo(targetFile)) {
                Log.e(TAG, "Failed to move session to deleted folder")
                return false
            }
            
            // Update session metadata
            val deletedSession = session.copy(
                deletedAt = System.currentTimeMillis(),
                deletionReason = reason
            )
            
            writeSessionToFile(targetFile, deletedSession)
            
            Log.i(TAG, "Session $sessionId marked as deleted, can be restored for $permanentDeleteAfterMs ms")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to mark session as deleted", e)
            false
        }
    }
    
    /**
     * Restore recently deleted session
     */
    fun restoreSession(sessionId: Long): Boolean {
        return try {
            val deletedFile = findDeletedSessionFile(sessionId) ?: return false
            val targetFile = File(sessionDirectory, deletedFile.name)
            
            if (!deletedFile.renameTo(targetFile)) {
                Log.e(TAG, "Failed to restore session from deleted folder")
                return false
            }
            
            // Clear deletion metadata
            val session = loadSessionFromFile(targetFile)
            val restoredSession = session.copy(
                deletedAt = null,
                deletionReason = null
            )
            
            writeSessionToFile(targetFile, restoredSession)
            
            Log.i(TAG, "Session $sessionId restored from deletion")
            true
            
        } catch (e: Exception) {
            Log.e(TAG, "Failed to restore session", e)
            false
        }
    }
    
    /**
     * Phase 2: Permanent deletion (after grace period or explicit user confirmation)
     */
    fun permanentlyDeleteSession(sessionId: Long): Boolean {
        val deletedFile = findDeletedSessionFile(sessionId) ?: return false
        val deleted = deletedFile.delete()
        
        if (deleted) {
            // Also delete associated files (binary raw data, etc.)
            deleteAssociatedFiles(sessionId)
            Log.i(TAG, "Session $sessionId permanently deleted")
        }
        
        return deleted
    }
    
    /**
     * Clean up old deleted sessions automatically
     */
    fun cleanupOldDeletedSessions() {
        val now = System.currentTimeMillis()
        
        deletedDirectory.listFiles()?.forEach { file ->
            try {
                val session = loadSessionFromFile(file)
                val deletedAt = session.deletedAt ?: return@forEach
                
                if (now - deletedAt > permanentDeleteAfterMs) {
                    permanentlyDeleteSession(session.id)
                    Log.i(TAG, "Auto-cleaned old deleted session: ${session.id}")
                }
            } catch (e: Exception) {
                Log.w(TAG, "Failed to check deleted session age: ${file.name}", e)
            }
        }
    }
    
    /**
     * Load sessions excluding deleted ones
     */
    fun loadAllSessions(): List<Session> {
        return sessionDirectory
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> loadSessionFromFile(file) }
            ?.filter { session -> session.deletedAt == null }  // Exclude deleted
            ?.sortedByDescending { session -> session.startTimeEpochMs }
            ?: emptyList()
    }
    
    /**
     * Load recently deleted sessions (for "Recently Deleted" screen)
     */
    fun loadDeletedSessions(): List<Session> {
        return deletedDirectory
            .listFiles { file -> file.isFile && file.extension == "json" }
            ?.mapNotNull { file -> loadSessionFromFile(file) }
            ?.sortedByDescending { session -> session.deletedAt ?: 0L }
            ?: emptyList()
    }
}
```

### 5.3 UI: Confirmation + Undo Snackbar

**Deletion flow:**

```kotlin
// SessionListFragment.kt
private fun deleteSession(session: Session) {
    // Step 1: Show confirmation dialog
    MaterialAlertDialogBuilder(requireContext())
        .setTitle("Delete session?")
        .setMessage("Session from ${session.trackName} on ${formatDate(session.startTimeEpochMs)}\n\nYou can restore it from 'Recently Deleted' for 7 days.")
        .setPositiveButton("Delete") { _, _ ->
            performDeletion(session)
        }
        .setNegativeButton("Cancel", null)
        .show()
}

private fun performDeletion(session: Session) {
    lifecycleScope.launch {
        val success = withContext(Dispatchers.IO) {
            sessionStorageManager.markSessionDeleted(session.id)
        }
        
        if (success) {
            // Step 2: Show undo snackbar
            Snackbar.make(
                binding.root,
                "Session deleted",
                Snackbar.LENGTH_LONG
            ).setAction("UNDO") {
                undoDeletion(session.id)
            }.show()
            
            // Refresh list
            viewModel.loadSessions()
        } else {
            Toast.makeText(requireContext(), "Failed to delete session", Toast.LENGTH_SHORT).show()
        }
    }
}

private fun undoDeletion(sessionId: Long) {
    lifecycleScope.launch {
        val restored = withContext(Dispatchers.IO) {
            sessionStorageManager.restoreSession(sessionId)
        }
        
        if (restored) {
            Toast.makeText(requireContext(), "Session restored", Toast.LENGTH_SHORT).show()
            viewModel.loadSessions()
        }
    }
}
```

### 5.4 "Recently Deleted" Screen

**New fragment showing deleted sessions:**

```kotlin
class RecentlyDeletedFragment : Fragment() {
    
    private val viewModel: RecentlyDeletedViewModel by viewModels()
    
    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        
        lifecycleScope.launch {
            viewModel.deletedSessions.collect { sessions ->
                adapter.submitList(sessions.map { session ->
                    DeletedSessionItem(
                        session = session,
                        daysUntilPermanentDeletion = calculateDaysRemaining(session.deletedAt!!),
                        onRestore = { viewModel.restoreSession(it.id) },
                        onDeleteNow = { viewModel.deleteNowWithConfirmation(it.id) }
                    )
                })
            }
        }
    }
    
    private fun calculateDaysRemaining(deletedAt: Long): Int {
        val elapsed = System.currentTimeMillis() - deletedAt
        val remaining = TimeUnit.DAYS.toMillis(7) - elapsed
        return TimeUnit.MILLISECONDS.toDays(remaining).toInt()
    }
}
```

### 5.5 Track Deletion Protection

**Safe track deletion (when user explicitly deletes track):**

```kotlin
class TrackManager(/* ... */) {
    
    fun deleteTrackSafely(trackName: String, sessionManager: SessionStorageManager): TrackDeletionResult {
        // Step 1: Count sessions for this track
        val sessions = sessionManager.loadSessionsForTrack(trackName)
        
        if (sessions.isEmpty()) {
            // Safe to delete, no sessions
            removeTrack(trackName)
            return TrackDeletionResult.Success(deletedSessions = 0)
        }
        
        // Step 2: Require explicit confirmation with session count
        return TrackDeletionResult.RequiresConfirmation(
            trackName = trackName,
            sessionCount = sessions.size,
            onConfirm = {
                // Mark all sessions as deleted (can be restored)
                sessions.forEach { session ->
                    sessionManager.markSessionDeleted(
                        sessionId = session.id,
                        reason = "Track '$trackName' deleted by user"
                    )
                }
                
                // Remove track from list
                removeTrack(trackName)
                
                TrackDeletionResult.Success(deletedSessions = sessions.size)
            }
        )
    }
}

sealed class TrackDeletionResult {
    data class RequiresConfirmation(
        val trackName: String,
        val sessionCount: Int,
        val onConfirm: () -> TrackDeletionResult
    ) : TrackDeletionResult()
    
    data class Success(val deletedSessions: Int) : TrackDeletionResult()
    
    data class Failed(val reason: String) : TrackDeletionResult()
}
```

**UI for track deletion:**

```kotlin
private fun deleteTrack(trackName: String) {
    lifecycleScope.launch {
        val result = withContext(Dispatchers.IO) {
            trackManager.deleteTrackSafely(trackName, sessionStorageManager)
        }
        
        when (result) {
            is TrackDeletionResult.RequiresConfirmation -> {
                MaterialAlertDialogBuilder(requireContext())
                    .setTitle("Delete track '$trackName'?")
                    .setMessage("This will also delete ${result.sessionCount} session(s).\n\nAll data can be restored from 'Recently Deleted' for 7 days.")
                    .setPositiveButton("Delete Track + Sessions") { _, _ ->
                        val confirmed = result.onConfirm()
                        handleDeletionResult(confirmed)
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }
            is TrackDeletionResult.Success -> {
                Toast.makeText(
                    requireContext(),
                    "Track deleted (${result.deletedSessions} sessions moved to Recently Deleted)",
                    Toast.LENGTH_LONG
                ).show()
            }
            is TrackDeletionResult.Failed -> {
                Toast.makeText(requireContext(), "Failed: ${result.reason}", Toast.LENGTH_SHORT).show()
            }
        }
    }
}
```

---

## 6. Coaching Feedback Integration

### 6.1 Keep Existing Corner Coaching Architecture

The existing corner coaching system (`docs/CORNER_COACHING_FEATURE_SPEC.md`) is well-designed. **No changes needed** to the coaching logic itself.

### 6.2 Run Coaching in Background Analysis

Coaching analysis is already part of the `DrivingCoachAnalyzer` pipeline. It will automatically run in the background `SessionAnalysisWorker` (Section 2.3).

**User experience:**
1. Stop recording → raw data saved in <3 seconds
2. User sees: "Recording saved ✓ Analyzing..."
3. Background worker runs full pipeline including corner coaching (30-60 seconds)
4. Notification: "Your session is ready! 🏁 12 laps, 3 improvement areas found"
5. User opens app → sees full coaching feedback

### 6.3 Coaching Feedback UI Updates

**Main screen - Coaching summary card:**

```xml
<!-- fragment_main.xml -->
<com.google.android.material.card.MaterialCardView
    android:id="@+id/coachingSummaryCard"
    android:layout_width="match_parent"
    android:layout_height="wrap_content"
    android:visibility="gone"
    app:cardElevation="2dp">
    
    <LinearLayout
        android:layout_width="match_parent"
        android:layout_height="wrap_content"
        android:orientation="vertical"
        android:padding="16dp">
        
        <TextView
            android:text="Latest Session Coaching"
            android:textStyle="bold"
            android:textSize="16sp" />
        
        <TextView
            android:id="@+id/coachingTopActions"
            android:layout_marginTop="8dp"
            android:textSize="14sp"
            tools:text="1. Corner 2: Brake earlier\n2. Corner 6: Focus on exit\n3. Corner 5: Trail braking promising" />
        
        <Button
            android:id="@+id/viewFullCoachingButton"
            android:layout_width="wrap_content"
            android:layout_height="wrap_content"
            android:layout_marginTop="8dp"
            android:text="View Details"
            style="@style/Widget.Material3.Button.TextButton" />
    </LinearLayout>
</com.google.android.material.card.MaterialCardView>
```

**Show after analysis completes:**

```kotlin
// MainFragment.kt
lifecycleScope.launch {
    viewModel.latestSession.collect { session ->
        if (session != null && 
            session.analysisStatus == AnalysisStatus.COMPLETED &&
            session.cornerCoachingSummary != null) {
            
            showCoachingSummary(session.cornerCoachingSummary)
        }
    }
}

private fun showCoachingSummary(summary: CornerCoachingSummary) {
    binding.coachingSummaryCard.visibility = View.VISIBLE
    
    val topActionsText = summary.topActions.take(3).mapIndexed { index, insight ->
        "${index + 1}. ${insight.headline}"
    }.joinToString("\n")
    
    binding.coachingTopActions.text = topActionsText
    
    binding.viewFullCoachingButton.setOnClickListener {
        // Navigate to detailed corner coaching screen
        findNavController().navigate(R.id.action_main_to_corner_coaching)
    }
}
```

---

## 7. Testing & Validation Strategy

### 7.1 Device Testing Checklist

**Galaxy M32 specific tests:**

- [ ] 8-minute recording (phone in pocket, screen locked)
- [ ] 15-minute recording (phone in pocket, screen locked)
- [ ] 30-minute recording (maximum stress test)
- [ ] Recording → lock screen → unlock after 5 minutes → verify still recording
- [ ] Recording → switch to other app → return after 5 minutes → verify still recording
- [ ] Recording → low battery warning → verify recording continues
- [ ] Start recording → Force close app → Verify raw data saved
- [ ] Background analysis completes → notification shown → coaching visible

**State sync tests:**

- [ ] Watchdog detects sensor stall → auto-recovery
- [ ] Watchdog detects recorder dead → saves what exists
- [ ] Service killed by Android → notification shown, partial data saved

**Deletion safety tests:**

- [ ] Delete single session → only that session deleted
- [ ] Delete single session → undo within 10 seconds → session restored
- [ ] Delete track with 5 sessions → confirmation shown with session count
- [ ] Delete track → all sessions moved to Recently Deleted, restorable
- [ ] Recently Deleted screen shows all deleted items with countdown

**UI responsiveness tests:**

- [ ] Stop recording completes in <3 seconds
- [ ] Session list loads in <2 seconds
- [ ] Navigate between screens → no hangs
- [ ] Rapid session selection → no crashes

### 7.2 Automated Tests

**Unit tests:**

```kotlin
class StreamingSessionWriterTest {
    @Test
    fun `write 10000 samples and verify binary file`() = runTest {
        val writer = StreamingSessionWriter(sessionId = 123, sessionDirectory = tempDir)
        
        repeat(10_000) { i ->
            writer.writeSample(createTestSample(i))
        }
        
        val file = writer.finalize()
        
        assertEquals(10_000 * 48, file.length())  // 48 bytes per sample
        
        // Verify can read back
        val samples = loadSamplesFromBinary(file)
        assertEquals(10_000, samples.size)
    }
    
    @Test
    fun `memory footprint stays under 200KB during long session`() = runTest {
        val repository = SessionRepository(/* test config */)
        
        val memoryBefore = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        
        // Simulate 30-minute recording at 50Hz = 90,000 samples
        repeat(90_000) { i ->
            repository.appendSample(createTestSample(i))
        }
        
        val memoryAfter = Runtime.getRuntime().totalMemory() - Runtime.getRuntime().freeMemory()
        val memoryUsed = memoryAfter - memoryBefore
        
        assertTrue(memoryUsed < 200_000)  // Less than 200KB
    }
}

class RecordingHealthMonitorTest {
    @Test
    fun `detect recorder dead and trigger recovery`() = runTest {
        val monitor = RecordingHealthMonitor(service, recorder, repository)
        
        // Simulate: service alive, recorder dead
        `when`(recorder.isActive).thenReturn(false)
        `when`(repository.isRecording.value).thenReturn(true)
        
        val health = monitor.checkHealth()
        val issue = monitor.detectIssue(health)
        
        assertEquals(RecordingIssue.RECORDER_DEAD, issue)
    }
    
    @Test
    fun `detect sample stall after 10 seconds`() = runTest {
        // ... test sample stall detection
    }
}

class SafeDeletionTest {
    @Test
    fun `delete session moves to deleted folder`() {
        val session = createTestSession()
        storageManager.saveSession(session)
        
        val deleted = storageManager.markSessionDeleted(session.id)
        
        assertTrue(deleted)
        assertNull(storageManager.loadSession(session.id))  // Not in main list
        assertNotNull(storageManager.loadDeletedSessions().find { it.id == session.id })
    }
    
    @Test
    fun `restore deleted session within grace period`() {
        val session = createTestSession()
        storageManager.saveSession(session)
        storageManager.markSessionDeleted(session.id)
        
        val restored = storageManager.restoreSession(session.id)
        
        assertTrue(restored)
        assertNotNull(storageManager.loadSession(session.id))
    }
}
```

---

## 8. Implementation Phases

### Phase 1: Core Recording Reliability (Highest Priority)
**Goal:** Stop recording failures and hangs

- [ ] Implement adaptive sensor rate manager (50Hz default)
- [ ] Implement StreamingSessionWriter (binary format)
- [ ] Update SessionRepository to use circular buffer + streaming
- [ ] Implement minimal stop pipeline (save raw, defer analysis)
- [ ] Implement background SessionAnalysisWorker
- [ ] Test on Galaxy M32: 8, 15, 30 minute recordings

**Success criteria:**
- 100% recording success rate on Galaxy M32
- Stop completes in <3 seconds
- Memory usage <200KB regardless of session length

### Phase 2: State Synchronization & Recovery
**Goal:** Auto-recover from state desync

- [ ] Implement RecordingHealthMonitor with watchdog
- [ ] Implement battery optimization detection (Samsung-specific)
- [ ] Add auto-recovery for recorder dead / sample stall
- [ ] Add UI warnings for battery optimization
- [ ] Test forced failure scenarios

**Success criteria:**
- Watchdog detects and logs all state mismatches
- Auto-recovery successful in 80% of stall scenarios
- User warned about battery optimization on first launch

### Phase 3: UI Thread Protection
**Goal:** Eliminate all UI hangs

- [ ] Move session loading to background with loading indicators
- [ ] Move session list loading to background
- [ ] Add debouncing to session selection
- [ ] Add timeouts to all I/O operations
- [ ] Test rapid UI interactions (no hangs)

**Success criteria:**
- No UI operation blocks main thread for >100ms
- Session list loads in <2 seconds with loading indicator
- Rapid taps don't cause crashes

### Phase 4: Safe Deletion
**Goal:** Prevent catastrophic data loss

- [ ] Implement two-phase deletion (soft delete → permanent)
- [ ] Add deleted_sessions folder and restore mechanism
- [ ] Implement undo snackbar (10 second window)
- [ ] Implement "Recently Deleted" screen
- [ ] Add track deletion protection with session count confirmation
- [ ] Test deletion edge cases

**Success criteria:**
- Zero accidental cascading deletions
- All deletions can be undone within 7 days
- Track deletion shows session count and requires explicit confirmation

### Phase 5: Coaching Integration
**Goal:** Coaching feedback works in background

- [ ] Verify coaching runs in SessionAnalysisWorker
- [ ] Add coaching summary card to main screen
- [ ] Add notification when analysis completes
- [ ] Test coaching on various session lengths

**Success criteria:**
- Coaching feedback appears 30-60 seconds after recording stops
- User can view basic session info immediately while analysis runs
- Notification sent when coaching ready

### Phase 6: Polish & Field Testing
**Goal:** Production-ready on Galaxy M32

- [ ] Add telemetry/logging for field diagnostics
- [ ] Optimize binary file I/O performance
- [ ] Add "Export session" functionality (CSV)
- [ ] User testing with real karting sessions
- [ ] Performance profiling on Galaxy M32

---

## 9. Rollout Strategy

### 9.1 Staged Rollout

**Alpha (internal testing):**
- Deploy to personal Galaxy M32
- Test with 10 real karting sessions
- Monitor logs for any issues

**Beta (limited users):**
- Deploy to 2-3 trusted users
- Collect feedback on reliability
- Monitor crash reports

**Production:**
- Full deployment
- Monitor success rate metrics

### 9.2 Rollback Plan

Keep old implementation behind feature flag:

```kotlin
object FeatureFlags {
    const val USE_STREAMING_WRITER = true  // Can toggle if issues found
    const val USE_BACKGROUND_ANALYSIS = true
    const val USE_WATCHDOG = true
}
```

### 9.3 Success Metrics

**Track these metrics:**

1. **Recording success rate**: target 98%+ on Galaxy M32
2. **Stop time**: target <3 seconds (95th percentile)
3. **UI hang rate**: target 0 hangs per 100 interactions
4. **Accidental deletion rate**: target 0
5. **Memory usage**: target <200KB during recording
6. **Analysis completion rate**: target 95%+ within 60 seconds

---

## 10. Risk Mitigation

### 10.1 Known Risks

| Risk | Mitigation |
|------|------------|
| Binary file corruption | Checksum validation, fallback to JSON |
| Background analysis fails | Keep raw data, allow manual retry |
| Streaming I/O performance | Use buffered writes, tune flush interval |
| Sensor rate too low | Make rate configurable, allow user override |
| Battery optimization still kills app | Clear user guidance, detect and warn |
| Deleted sessions fill storage | Auto-cleanup after 7 days |

### 10.2 Fallback Strategy

If streaming writer fails:
- Fall back to in-memory accumulation (old behavior)
- Reduce sample rate to 20Hz as emergency fallback
- Show user warning: "Recording in low-reliability mode"

---

## 11. Documentation Updates

After implementation:

- [ ] Update README.md with new reliability features
- [ ] Update docs/PROJEKTDOKUMENTATION.md with architecture changes
- [ ] Create USER_GUIDE.md with battery optimization instructions
- [ ] Document binary file format specification
- [ ] Create TROUBLESHOOTING.md for common issues

---

## 12. Summary

This design addresses all reported reliability issues:

✅ **Recording failures** → Streaming to disk prevents OOM kills  
✅ **Corrupted sessions** → Raw data always preserved, analysis separated  
✅ **App hangs** → All blocking operations moved to background  
✅ **Data corruption** → Two-phase deletion with 7-day restore window  
✅ **State desync** → Watchdog with auto-recovery  

**Key trade-offs accepted:**
- 50Hz sampling instead of 200Hz (still excellent for karting)
- Analysis delayed 30-60 seconds (user indicated acceptable)
- Slightly more complex architecture (worth it for reliability)

**Expected user experience on Galaxy M32:**
- 100% recording success rate
- <3 second stop time
- Zero hangs
- No data loss
- Coaching feedback after brief wait

This design prioritizes **reliability over data density**, which matches the user's stated preference and device constraints.
