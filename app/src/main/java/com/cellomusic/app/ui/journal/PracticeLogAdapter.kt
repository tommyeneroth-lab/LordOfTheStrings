package com.cellomusic.app.ui.journal

import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.SeekBar
import android.widget.TextView
import androidx.recyclerview.widget.DiffUtil
import androidx.recyclerview.widget.ListAdapter
import androidx.recyclerview.widget.RecyclerView
import com.cellomusic.app.R
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import com.google.android.material.button.MaterialButton
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale

/**
 * Two-line header per row, plus an inline player strip that expands only
 * when the user taps a row that has a recording.
 *
 * State is driven entirely by the fragment: the adapter holds the
 * `expandedSessionId` + playback position / duration / playing flag for
 * whichever row is currently active, and the fragment re-notifies the
 * adapter whenever those values change.
 */
class PracticeLogAdapter(
    private val onRowClick: (PracticeSessionEntity) -> Unit = {},
    private val onRowLongClick: (PracticeSessionEntity) -> Boolean = { false },
    private val onPlayToggle: (PracticeSessionEntity) -> Unit = {},
    private val onSeekTo: (PracticeSessionEntity, Int) -> Unit = { _, _ -> },
    private val onDeleteRecording: (PracticeSessionEntity) -> Unit = {}
) : ListAdapter<PracticeSessionEntity, PracticeLogAdapter.VH>(DIFF) {

    private val dateFormat = SimpleDateFormat("dd MMM  HH:mm", Locale.getDefault())

    /** Row currently showing the player strip, or null if collapsed. */
    var expandedSessionId: Long? = null
        private set

    /** Row currently producing audio, or null if nothing playing. */
    private var playingSessionId: Long? = null
    private var isPlaying: Boolean = false
    private var positionMs: Int = 0
    private var durationMs: Int = 0

    /** Toggle the expanded row. Returns the new expanded id (or null). */
    fun toggleExpanded(session: PracticeSessionEntity): Long? {
        val prev = expandedSessionId
        expandedSessionId = if (prev == session.id) null else session.id
        if (prev != null) notifyRowChanged(prev)
        expandedSessionId?.let { notifyRowChanged(it) }
        return expandedSessionId
    }

    fun collapse() {
        val prev = expandedSessionId ?: return
        expandedSessionId = null
        notifyRowChanged(prev)
    }

    /** Fragment calls this whenever MediaPlayer state changes. */
    fun updatePlayback(sessionId: Long?, playing: Boolean, posMs: Int, durMs: Int) {
        val oldId = playingSessionId
        playingSessionId = sessionId
        isPlaying = playing
        positionMs = posMs
        durationMs = durMs
        if (oldId != null && oldId != sessionId) notifyRowChanged(oldId)
        sessionId?.let { notifyRowChanged(it) }
    }

    private fun notifyRowChanged(id: Long) {
        val pos = currentList.indexOfFirst { it.id == id }
        if (pos >= 0) notifyItemChanged(pos)
    }

    override fun onCreateViewHolder(parent: ViewGroup, viewType: Int): VH {
        val view = LayoutInflater.from(parent.context)
            .inflate(R.layout.item_practice_session, parent, false)
        return VH(view)
    }

    override fun onBindViewHolder(holder: VH, position: Int) {
        val entry = getItem(position)
        val evalStr = when (entry.selfEval) { 1 -> "😞"; 2 -> "😐"; 3 -> "🙂"; 4 -> "😊"; else -> "🤩" }
        val catStr  = if (entry.category.isNotEmpty()) "  [${entry.category}]" else ""
        val recStr  = if (entry.recordingPath != null) " 🎙" else ""

        holder.tvTitle.text = "${dateFormat.format(Date(entry.timestampMs))}  •  ${entry.pieceName}  $evalStr$recStr"
        holder.tvInfo.text  = "${entry.durationMin} min  +${entry.totalPoints} pts$catStr" +
            if (entry.notes.isNotEmpty()) "  —  ${entry.notes.take(60)}" else ""

        // Row click → toggle expansion (only if there's a recording to play).
        holder.itemView.setOnClickListener { onRowClick(entry) }
        holder.itemView.setOnLongClickListener { onRowLongClick(entry) }

        val expanded = (entry.id == expandedSessionId) && entry.recordingPath != null
        holder.playerStrip.visibility = if (expanded) View.VISIBLE else View.GONE

        if (expanded) {
            val thisRowIsPlaying = (entry.id == playingSessionId) && isPlaying
            holder.btnPlay.text = if (thisRowIsPlaying) "⏸" else "▶"

            val pos = if (entry.id == playingSessionId) positionMs else 0
            val dur = if (entry.id == playingSessionId && durationMs > 0) durationMs else 0

            holder.tvElapsed.text = formatMmSs(pos)
            holder.tvTotal.text   = if (dur > 0) formatMmSs(dur) else "--:--"

            holder.seek.max = if (dur > 0) dur else 1
            // Only update progress from outside if the user isn't dragging.
            if (!holder.userIsDragging) holder.seek.progress = pos.coerceAtMost(holder.seek.max)

            holder.btnPlay.setOnClickListener { onPlayToggle(entry) }
            holder.btnDelete.setOnClickListener { onDeleteRecording(entry) }

            holder.seek.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
                override fun onProgressChanged(sb: SeekBar?, progress: Int, fromUser: Boolean) {
                    if (fromUser) holder.tvElapsed.text = formatMmSs(progress)
                }
                override fun onStartTrackingTouch(sb: SeekBar?) { holder.userIsDragging = true }
                override fun onStopTrackingTouch(sb: SeekBar?) {
                    holder.userIsDragging = false
                    onSeekTo(entry, holder.seek.progress)
                }
            })
        } else {
            // Detach listeners so a collapsed row can't fire them.
            holder.btnPlay.setOnClickListener(null)
            holder.btnDelete.setOnClickListener(null)
            holder.seek.setOnSeekBarChangeListener(null)
        }
    }

    override fun onViewRecycled(holder: VH) {
        holder.seek.setOnSeekBarChangeListener(null)
        holder.userIsDragging = false
        super.onViewRecycled(holder)
    }

    private fun formatMmSs(ms: Int): String {
        val totalSec = (ms / 1000).coerceAtLeast(0)
        return "%d:%02d".format(totalSec / 60, totalSec % 60)
    }

    inner class VH(view: View) : RecyclerView.ViewHolder(view) {
        val tvTitle: TextView       = view.findViewById(R.id.tv_row_title)
        val tvInfo: TextView        = view.findViewById(R.id.tv_row_info)
        val playerStrip: View       = view.findViewById(R.id.player_strip)
        val btnPlay: MaterialButton = view.findViewById(R.id.btn_row_play)
        val tvElapsed: TextView     = view.findViewById(R.id.tv_row_time_elapsed)
        val tvTotal: TextView       = view.findViewById(R.id.tv_row_time_total)
        val seek: SeekBar           = view.findViewById(R.id.seek_row_progress)
        val btnDelete: MaterialButton = view.findViewById(R.id.btn_row_delete_recording)
        var userIsDragging: Boolean = false
    }

    companion object {
        val DIFF = object : DiffUtil.ItemCallback<PracticeSessionEntity>() {
            override fun areItemsTheSame(a: PracticeSessionEntity, b: PracticeSessionEntity) = a.id == b.id
            override fun areContentsTheSame(a: PracticeSessionEntity, b: PracticeSessionEntity) = a == b
        }
    }
}
