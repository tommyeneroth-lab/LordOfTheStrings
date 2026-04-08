package com.cellomusic.app.ui.library.adapter

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.databinding.ItemScoreRowBinding
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScoreAdapter(
    private val onScoreClick: (ScoreEntity) -> Unit,
    private val onScoreLongClick: (ScoreEntity) -> Unit
) : ListAdapter<ScoreEntity, ScoreAdapter.ScoreViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScoreViewHolder {
        val binding = ItemScoreRowBinding.inflate(
            LayoutInflater.from(parent.context), parent, false
        )
        return ScoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScoreViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ScoreViewHolder(private val binding: ItemScoreRowBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entity: ScoreEntity) {
            binding.tvTitle.text = entity.title
            binding.tvComposer.text = entity.composer ?: ""
            binding.tvComposer.visibility = if (entity.composer.isNullOrBlank()) View.GONE else View.VISIBLE

            // Measures + date meta line
            val measText = if (entity.measureCount > 0) "${entity.measureCount} meas." else ""
            val dateText = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(entity.dateAdded))
            binding.tvMeasures.text = measText
            binding.tvDate.text = if (measText.isNotEmpty()) "· $dateText" else dateText

            binding.ivFavorite.visibility = if (entity.isFavorite) View.VISIBLE else View.GONE

            // OMR status
            binding.omrSpinner.visibility =
                if (entity.omrStatus == "PROCESSING") View.VISIBLE else View.GONE

            // Source-type badge: label + colour
            val (label, color) = badgeInfo(entity.sourceType)
            binding.tvTypeBadge.text = label
            val dp = binding.root.context.resources.displayMetrics.density
            val bg = GradientDrawable().apply {
                shape = GradientDrawable.RECTANGLE
                cornerRadius = 10f * dp
                setColor(color)
            }
            binding.tvTypeBadge.background = bg

            binding.root.setOnClickListener { onScoreClick(entity) }
            binding.root.setOnLongClickListener { onScoreLongClick(entity); true }
        }

        /**
         * Returns a short label and a background colour for the source-type badge.
         *
         * Unicode symbols used:
         *   ♪  = eighth note  (U+266A) — audio / MP3
         *   ≡  = identical to (U+2261) — looks like horizontal lines / MIDI piano roll
         */
        private fun badgeInfo(sourceType: String): Pair<String, Int> = when (sourceType) {
            "MUSICXML"          -> "XML"  to Color.parseColor("#1B5E20") // dark green
            "PDF_OMR", "PDF"    -> "PDF"  to Color.parseColor("#B71C1C") // dark red
            "JPEG_OMR", "PHOTO" -> "IMG"  to Color.parseColor("#4A148C") // dark purple
            "MIDI"              -> "MID"  to Color.parseColor("#0D47A1") // dark blue
            "MP3_TRANSCRIPTION" -> "♪"   to Color.parseColor("#E65100") // deep orange
            else                -> "?"    to Color.parseColor("#37474F") // blue-grey
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ScoreEntity>() {
        override fun areItemsTheSame(old: ScoreEntity, new: ScoreEntity) = old.id == new.id
        override fun areContentsTheSame(old: ScoreEntity, new: ScoreEntity) = old == new
    }
}
