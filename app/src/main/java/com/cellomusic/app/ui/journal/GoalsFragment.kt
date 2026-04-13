package com.cellomusic.app.ui.journal

import android.app.AlertDialog
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.*
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import androidx.recyclerview.widget.RecyclerView
import com.cellomusic.app.R
import com.cellomusic.app.data.db.entity.PracticeGoalEntity
import com.google.android.material.floatingactionbutton.FloatingActionButton
import kotlinx.coroutines.launch
import java.util.Calendar

class GoalsFragment : Fragment() {

    private val viewModel: GoalsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_goals, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val recyclerActive = view.findViewById<RecyclerView>(R.id.recycler_active_goals)
        val recyclerCompleted = view.findViewById<RecyclerView>(R.id.recycler_completed_goals)
        val tvEmpty = view.findViewById<TextView>(R.id.tv_empty_goals)
        val fab = view.findViewById<FloatingActionButton>(R.id.fab_add_goal)

        val activeAdapter = GoalAdapter { goal -> viewModel.deleteGoal(goal) }
        val completedAdapter = GoalAdapter(null)

        recyclerActive.layoutManager = LinearLayoutManager(requireContext())
        recyclerActive.adapter = activeAdapter
        recyclerCompleted.layoutManager = LinearLayoutManager(requireContext())
        recyclerCompleted.adapter = completedAdapter

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.activeGoals.collect { goals ->
                activeAdapter.submitList(goals)
                tvEmpty.visibility = if (goals.isEmpty()) View.VISIBLE else View.GONE
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.completedGoals.collect { goals ->
                completedAdapter.submitList(goals)
            }
        }

        fab.setOnClickListener { showCreateGoalDialog() }
    }

    private fun showCreateGoalDialog() {
        val ctx = requireContext()
        val dialogView = LayoutInflater.from(ctx).inflate(R.layout.dialog_create_goal, null)
        val etTitle = dialogView.findViewById<EditText>(R.id.et_goal_title)
        val spinnerCategory = dialogView.findViewById<Spinner>(R.id.spinner_goal_category)
        val spinnerPeriod = dialogView.findViewById<Spinner>(R.id.spinner_goal_period)
        val etTarget = dialogView.findViewById<EditText>(R.id.et_goal_target)
        val spinnerUnit = dialogView.findViewById<Spinner>(R.id.spinner_goal_unit)

        val categories = arrayOf("General", "Intonation", "Vibrato", "Bow Technique", "Memorization", "Scales", "Sight-reading")
        val catValues = arrayOf("", "INTONATION", "VIBRATO", "BOW_TECHNIQUE", "MEMORIZATION", "SCALES", "SIGHT_READING")
        val periods = arrayOf("Weekly", "Monthly", "Half-yearly")
        val periodValues = arrayOf("WEEKLY", "MONTHLY", "HALF_YEARLY")
        val units = arrayOf("Sessions", "Minutes")
        val unitValues = arrayOf("SESSIONS", "MINUTES")

        spinnerCategory.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, categories)
        spinnerPeriod.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, periods)
        spinnerUnit.adapter = ArrayAdapter(ctx, android.R.layout.simple_spinner_dropdown_item, units)

        AlertDialog.Builder(ctx, R.style.Theme_CelloMusicApp)
            .setTitle("New Practice Goal")
            .setView(dialogView)
            .setPositiveButton("Create") { _, _ ->
                val title = etTitle.text.toString().trim().ifEmpty { "Practice goal" }
                val catIdx = spinnerCategory.selectedItemPosition
                val periodIdx = spinnerPeriod.selectedItemPosition
                val target = etTarget.text.toString().toIntOrNull() ?: 7
                val unitIdx = spinnerUnit.selectedItemPosition

                val cal = Calendar.getInstance()
                val startMs = cal.timeInMillis
                val endMs = when (periodValues[periodIdx]) {
                    "WEEKLY" -> { cal.add(Calendar.WEEK_OF_YEAR, 1); cal.timeInMillis }
                    "MONTHLY" -> { cal.add(Calendar.MONTH, 1); cal.timeInMillis }
                    else -> { cal.add(Calendar.MONTH, 6); cal.timeInMillis }
                }

                viewModel.createGoal(
                    PracticeGoalEntity(
                        title = title,
                        category = catValues[catIdx],
                        periodType = periodValues[periodIdx],
                        targetCount = target,
                        targetUnit = unitValues[unitIdx],
                        startDateMs = startMs,
                        endDateMs = endMs
                    )
                )
            }
            .setNegativeButton("Cancel", null)
            .show()
    }
}
