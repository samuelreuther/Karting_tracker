package com.kartingtracker.worker

import android.content.Context
import android.util.Log
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import com.kartingtracker.KartingApplication

private const val TAG = "SessionAnalysisWorker"

class SessionAnalysisWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result {
        val sessionId = inputData.getLong(KEY_SESSION_ID, -1L)
        val rawFilePath = inputData.getString(KEY_RAW_FILE_PATH)

        if (sessionId == -1L || rawFilePath == null) {
            Log.e(TAG, "Invalid input: sessionId=$sessionId rawFilePath=$rawFilePath")
            return Result.failure()
        }

        Log.i(TAG, "Background analysis started for session $sessionId")

        return try {
            val sessionRepository =
                (applicationContext as KartingApplication).appContainer.sessionRepository

            val success = sessionRepository.analyzeRawSession(sessionId, rawFilePath)
            if (success) {
                Log.i(TAG, "Background analysis completed for session $sessionId")
                Result.success()
            } else {
                Log.e(TAG, "Background analysis returned false for session $sessionId")
                Result.failure()
            }
        } catch (e: Exception) {
            Log.e(TAG, "Background analysis threw for session $sessionId", e)
            Result.failure()
        }
    }

    companion object {
        const val KEY_SESSION_ID = "session_id"
        const val KEY_RAW_FILE_PATH = "raw_file_path"
    }
}
