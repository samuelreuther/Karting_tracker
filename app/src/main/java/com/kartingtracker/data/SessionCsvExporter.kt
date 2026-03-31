package com.kartingtracker.data

import android.content.Context
import java.io.File

class SessionCsvExporter(
    private val context: Context
) {
    fun export(session: Session): File {
        val exportDir = File(context.getExternalFilesDir(null), EXPORT_DIR).apply { mkdirs() }
        val safeTrack = session.trackName.trim().ifBlank { "track" }.replace(Regex("[^A-Za-z0-9_-]+"), "_")
        val file = File(exportDir, "session_${safeTrack}_${session.startTimeEpochMs}.csv")

        val lapRanges = session.laps.map { lap ->
            lap.id to LongRange(lap.startTimestampNs, lap.endTimestampNs)
        }

        file.bufferedWriter().use { writer ->
            writer.appendLine(HEADER)
            session.samples.forEach { sample ->
                val lapId = lapRanges.firstOrNull { (_, range) -> sample.timestampNs in range }?.first ?: -1
                writer.appendLine(
                    listOf(
                        sample.timestampNs,
                        sample.longitudinalAccel,
                        sample.lateralAccel,
                        sample.totalAcceleration,
                        sample.yawRateAbs,
                        lapId
                    ).joinToString(",")
                )
            }
        }
        return file
    }

    companion object {
        private const val EXPORT_DIR = "exports"
        private const val HEADER = "timestampNs,longitudinalAccel,lateralAccel,totalAcceleration,yawRateAbs,lapId"
    }
}
