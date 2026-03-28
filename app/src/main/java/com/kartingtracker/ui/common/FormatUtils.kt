package com.kartingtracker.ui.common

import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

fun formatLapTime(milliseconds: Long): String {
    val totalMs = milliseconds.coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val hundredths = (totalMs % 1_000L) / 10L
    return "%d:%02d.%02d".format(minutes, seconds, hundredths)
}

fun formatSessionDate(epochMilliseconds: Long): String {
    val formatter = SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault())
    return formatter.format(Date(epochMilliseconds))
}
