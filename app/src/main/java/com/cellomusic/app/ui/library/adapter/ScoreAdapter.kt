package com.cellomusic.app.ui.library.adapter

import android.view.LayoutInflater
import android.view.ViewGroup
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import coil.load
import com.cellomusic.app.R
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.databinding.ItemScoreCardBinding
import java.io.File
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

class ScoreAdapter(
    private val onScoreClick: (ScoreEntity) -> Unit,
    private val onScoreLongClick: (ScoreEntity) -> Unit
) : ListAdapter<ScoreEntity, ScoreAdapter.ScoreViewHolder>(DiffCallback) {

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): ScoreViewHolder {
        val binding = ItemScoreCardBinding.inflate(LayoutInflater.from(parent.context), parent, false)
        return ScoreViewHolder(binding)
    }

    override fun onBindViewHolder(holder: ScoreViewHolder, position: Int) {
        holder.bind(getItem(position))
    }

    inner class ScoreViewHolder(private val binding: ItemScoreCardBinding) :
        RecyclerView.ViewHolder(binding.root) {

        fun bind(entity: ScoreEntity) {
            binding.tvTitle.text = entity.title
            binding.tvComposer.text = entity.composer ?: "Unknown composer"
            binding.tvMeasures.text = if (entity.measureCount > 0) "${entity.measureCount} measures" else ""
            binding.tvDate.text = SimpleDateFormat("MMM d, yyyy", Locale.getDefault())
                .format(Date(entity.dateAdded))
            binding.tvKeySignature.text = entity.keySignature
            binding.ivFavorite.visibility = if (entity.isFavorite)
                android.view.View.VISIBLE else android.view.View.GONE

            // Load thumbnail
            val thumbFile = entity.thumbnailPath?.let { File(it) }
            if (thumbFile != null && thumbFile.exists()) {
                binding.ivThumbnail.load(thumbFile) {
                    placeholder(R.drawable.ic_score_placeholder)
                    error(R.drawable.ic_score_placeholder)
                }
            } else {
                binding.ivThumbnail.setImageResource(R.drawable.ic_score_placeholder)
            }

            // Source type badge
            binding.tvSourceType.text = when (entity.sourceType) {
                "MUSICXML" -> "XML"
                "JPEG_OMR" -> "Photo"
                "PDF_OMR" -> "PDF"
                else -> ""
            }

            binding.root.setOnClickListener { onScoreClick(entity) }
            binding.root.setOnLongClickListener {
                onScoreLongClick(entity)
                true
            }
        }
    }

    companion object DiffCallback : DiffUtil.ItemCallback<ScoreEntity>() {
        override fun areItemsTheSame(old: ScoreEntity, new: ScoreEntity) = old.id == new.id
        override fun areContentsTheSame(old: ScoreEntity, new: ScoreEntity) = old == new
    }
}
