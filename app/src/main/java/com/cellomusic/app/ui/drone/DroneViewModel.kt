package com.cellomusic.app.ui.drone

import androidx.lifecycle.ViewModel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow

class DroneViewModel : ViewModel() {
    private val _droneNote = MutableStateFlow("C2")
    val droneNote: StateFlow<String> = _droneNote

    fun setDroneNote(note: String) { _droneNote.value = note }
}
