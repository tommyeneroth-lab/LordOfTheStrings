package com.cellomusic.app.ui.journal

import android.graphics.Color
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import java.text.SimpleDateFormat
import java.util.*

class PracticeLogAdapter : ListAdapter<PracticeSessionEntity, PracticeLogAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault())

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvDate: TextView  = view.findViewById(android.R.id.text1)
        val tvInfo: TextView  = view.findViewById(android.R.id.text2)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(android.R.layout.simple_list_item_2, parent, false)
        view.setBackgroundColor(Color.parseColor("#1C1C1C"))
        view.setPadding(24, 12, 24, 12)
        view.findViewById<TextView>(android.R.id.text1).setTextColor(Color.parseColor("#F4E4C1"))
        view.findViewById<TextView>(android.R.id.text2).setTextColor(Color.parseColor("#C8B89A"))
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val evalStr = when (entry.selfEval) { 1 -> "😞"; 2 -> "😐"; 3 -> "🙂"; 4 -> "😊"; else -> "🤩" }
        val catStr = if (entry.category.isNotEmpty()) "  [${entry.category}]" else ""
        val recStr = if (entry.recordingPath != null) " 🎙" else ""
        holder.tvDate.text = "${dateFormat.format(Date(entry.timestampMs))}  •  ${entry.pieceName}  $evalStr$recStr"
        holder.tvInfo.text = "${entry.durationMin} min  +${entry.totalPoints} pts$catStr${if (entry.notes.isNotEmpty()) "  —  ${entry.notes.take(50)}" else ""}"
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PracticeSessionEntity>() {
            override fun areItemsTheSame(a: PracticeSessionEntity, b: PracticeSessionEntity) = a.id == b.id
            override fun areContentsTheSame(a: PracticeSessionEntity, b: PracticeSessionEntity) = a == b
        }
    }
}
