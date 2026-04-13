package com.cellomusic.app.ui.journal

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cellomusic.app.data.db.AppDatabase
import com.cellomusic.app.data.db.entity.PracticeGoalEntity
import com.cellomusic.app.data.repository.GoalRepository
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch

class GoalsViewModel(app: Application) : AndroidViewModel(app) {

    private val db = AppDatabase.getInstance(app)
    private val goalRepo = GoalRepository(db.practiceGoalDao(), db.practiceSessionDao())

    val activeGoals: StateFlow<List<PracticeGoalEntity>> =
        goalRepo.getActiveGoals()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    val completedGoals: StateFlow<List<PracticeGoalEntity>> =
        goalRepo.getCompletedGoals()
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5000), emptyList())

    fun createGoal(goal: PracticeGoalEntity) = viewModelScope.launch {
        goalRepo.createGoal(goal)
    }

    fun deleteGoal(goal: PracticeGoalEntity) = viewModelScope.launch {
        goalRepo.deleteGoal(goal)
    }
}
