package com.kartingtracker.util

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.PowerManager
import android.provider.Settings
import androidx.core.content.getSystemService

class BatteryOptimizationHelper(private val context: Context) {

    fun isBatteryOptimizationEnabled(): Boolean {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.M) {
            return false
        }
        val powerManager = context.getSystemService<PowerManager>()
        val packageName = context.packageName
        return powerManager?.isIgnoringBatteryOptimizations(packageName) == false
    }

    fun isSamsungDevice(): Boolean {
        val manufacturer = Build.MANUFACTURER.lowercase()
        return manufacturer.contains("samsung")
    }

    fun openBatteryOptimizationSettings(): Intent {
        return Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS).apply {
            data = Uri.parse("package:${context.packageName}")
        }
    }

    fun getSamsungGuidanceMessage(): String {
        return "For reliable recording on Samsung devices:\n\n" +
            "1. Settings → Apps → Karting Tracker → Battery\n" +
            "2. Set to \"Unrestricted\"\n" +
            "3. Disable \"Put app to sleep\"\n\n" +
            "This prevents Android from killing the recording service."
    }

    fun getGeneralGuidanceMessage(): String {
        return "For reliable recording, disable battery optimization:\n\n" +
            "This app needs to run in the background during karting sessions.\n" +
            "Battery optimization may stop the recording unexpectedly."
    }
}
