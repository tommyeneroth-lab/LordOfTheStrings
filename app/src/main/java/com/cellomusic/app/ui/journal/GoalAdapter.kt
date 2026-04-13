package com.cellomusic.app.ui.journal

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ProgressBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cellomusic.app.R
import com.cellomusic.app.data.db.entity.PracticeGoalEntity
import java.text.SimpleDateFormat
import java.util.*

class GoalAdapter(
    private val onDelete: ((PracticeGoalEntity) -> Unit)?
) : ListAdapter<PracticeGoalEntity, GoalAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd MMM", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView = view.findViewById(R.id.tv_goal_title)
        val tvCategory: TextView = view.findViewById(R.id.tv_goal_category)
        val tvPeriod: TextView = view.findViewById(R.id.tv_goal_period)
        val tvProgress: TextView = view.findViewById(R.id.tv_goal_progress)
        val progressBar: ProgressBar = view.findViewById(R.id.progress_goal)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context).inflate(R.layout.item_goal_card, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val goal = getItem(position)
        holder.tvTitle.text = goal.title
        holder.tvCategory.text = goal.category.replace("_", " ").ifEmpty { "General" }
        holder.tvPeriod.text = "${goal.periodType.lowercase().replaceFirstChar { it.uppercase() }} · ends ${dateFormat.format(Date(goal.endDateMs))}"
        holder.tvProgress.text = "${goal.completedCount} / ${goal.targetCount} ${goal.targetUnit.lowercase()}"
        holder.progressBar.max = goal.targetCount
        holder.progressBar.progress = goal.completedCount.coerceAtMost(goal.targetCount)

        if (goal.isCompleted) {
            holder.tvTitle.setTextColor(Color.parseColor("#4CAF50"))
            holder.tvProgress.text = "✓ Completed!"
        }

        if (onDelete != null) {
            holder.itemView.setOnLongClickListener {
                onDelete.invoke(goal)
                true
            }
        }
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PracticeGoalEntity>() {
            override fun areItemsTheSame(a: PracticeGoalEntity, b: PracticeGoalEntity) = a.id == b.id
            override fun areContentsTheSame(a: PracticeGoalEntity, b: PracticeGoalEntity) = a == b
        }
    }
}
