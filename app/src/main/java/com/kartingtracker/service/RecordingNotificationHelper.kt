package com.kartingtracker.service

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.os.Build
import android.text.format.DateUtils
import androidx.core.app.NotificationCompat
import com.kartingtracker.R
import com.kartingtracker.ui.MainActivity

class RecordingNotificationHelper(
    private val context: Context
) {
    private val notificationManager =
        context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    fun ensureChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
            return
        }

        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = context.getString(R.string.recording_channel_description)
            setShowBadge(false)
        }
        notificationManager.createNotificationChannel(channel)
    }

    fun buildNotification(
        trackName: String,
        phaseLabel: String,
        elapsedMs: Long,
        sampleCount: Int,
        lapCount: Int
    ): Notification {
        val contentIntent = PendingIntent.getActivity(
            context,
            REQUEST_CONTENT,
            Intent(context, MainActivity::class.java).apply {
                flags = Intent.FLAG_ACTIVITY_SINGLE_TOP or Intent.FLAG_ACTIVITY_CLEAR_TOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )
        val stopIntent = PendingIntent.getService(
            context,
            REQUEST_STOP,
            Intent(context, RecordingForegroundService::class.java).apply {
                action = RecordingForegroundService.ACTION_STOP
            },
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val title = context.getString(R.string.recording_notification_title, trackName)
        val durationLabel = DateUtils.formatElapsedTime((elapsedMs / 1_000L).coerceAtLeast(0L))
        val statsLabel = if (lapCount > 0) {
            context.getString(R.string.recording_notification_stats_laps, lapCount, sampleCount)
        } else {
            context.getString(R.string.recording_notification_stats_samples, sampleCount)
        }

        return NotificationCompat.Builder(context, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_stat_karting)
            .setContentTitle(title)
            .setContentText(
                context.getString(
                    R.string.recording_notification_text,
                    phaseLabel,
                    durationLabel,
                    statsLabel
                )
            )
            .setStyle(
                NotificationCompat.BigTextStyle().bigText(
                    context.getString(
                        R.string.recording_notification_big_text,
                        trackName,
                        phaseLabel,
                        durationLabel,
                        statsLabel
                    )
                )
            )
            .setOngoing(true)
            .setOnlyAlertOnce(true)
            .setSilent(true)
            .setForegroundServiceBehavior(NotificationCompat.FOREGROUND_SERVICE_IMMEDIATE)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setContentIntent(contentIntent)
            .addAction(
                0,
                context.getString(R.string.stop_recording),
                stopIntent
            )
            .build()
    }

    fun notify(notification: Notification) {
        notificationManager.notify(NOTIFICATION_ID, notification)
    }

    companion object {
        const val CHANNEL_ID = "recording_session"
        const val NOTIFICATION_ID = 10_001

        private const val REQUEST_CONTENT = 1
        private const val REQUEST_STOP = 2
    }
}
