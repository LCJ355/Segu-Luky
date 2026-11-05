package com.intercom.segluky

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update

object SafetyStateStore {
    private val _snapshot = MutableStateFlow(SafetySnapshot())
    val snapshot: StateFlow<SafetySnapshot> = _snapshot.asStateFlow()

    fun update(transform: (SafetySnapshot) -> SafetySnapshot) {
        _snapshot.update(transform)
    }

    fun log(message: String) {
        _snapshot.update { current ->
            current.copy(eventLog = (listOf(message) + current.eventLog).take(20))
        }
    }

    fun reset() {
        _snapshot.value = SafetySnapshot()
    }
}
