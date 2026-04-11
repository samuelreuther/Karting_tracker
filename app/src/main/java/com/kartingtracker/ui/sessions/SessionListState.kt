package com.kartingtracker.ui.sessions

import com.kartingtracker.data.Session

sealed class SessionListState {
    object Loading : SessionListState()
    data class Success(val sessions: List<Session>) : SessionListState()
    data class Error(val message: String) : SessionListState()
}
