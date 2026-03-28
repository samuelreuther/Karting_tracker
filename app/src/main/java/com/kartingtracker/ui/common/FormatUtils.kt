package com.kartingtracker.ui.common

fun formatLapTime(milliseconds: Long): String {
    val totalMs = milliseconds.coerceAtLeast(0L)
    val minutes = totalMs / 60_000L
    val seconds = (totalMs % 60_000L) / 1_000L
    val hundredths = (totalMs % 1_000L) / 10L
    return "%d:%02d.%02d".format(minutes, seconds, hundredths)
}
