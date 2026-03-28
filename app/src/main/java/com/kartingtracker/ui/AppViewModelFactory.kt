package com.kartingtracker.ui

import android.app.Application
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import com.kartingtracker.KartingApplication

class AppViewModelFactory(
    private val application: Application
) : ViewModelProvider.Factory {
    override fun <T : ViewModel> create(modelClass: Class<T>): T {
        if (modelClass.isAssignableFrom(SessionViewModel::class.java)) {
            val app = application as KartingApplication
            @Suppress("UNCHECKED_CAST")
            return SessionViewModel(
                application = application,
                sessionRepository = app.appContainer.sessionRepository,
                sensorRecorder = app.appContainer.sensorRecorder
            ) as T
        }
        throw IllegalArgumentException("Unknown ViewModel class: ${modelClass.name}")
    }
}
