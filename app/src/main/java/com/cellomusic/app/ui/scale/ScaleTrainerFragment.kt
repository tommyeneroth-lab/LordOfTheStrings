package com.cellomusic.app.ui.scale

import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Button
import android.widget.LinearLayout
import android.widget.Spinner
import android.widget.TextView
import android.widget.Toast
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.R
import com.cellomusic.app.domain.scale.ScaleCategory
import com.cellomusic.app.domain.scale.ScaleDef
import com.cellomusic.app.domain.scale.ScaleLibrary
import com.google.android.material.button.MaterialButton
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

/**
 * Guided scale/arpeggio practice screen.
 *
 * Picker shows category → scale; selecting one seeds a sensible BPM,
 * renders ascending + descending note streams, and arms the embedded
 * metronome. Drill time accumulates while the metronome runs; tapping
 * "Log" writes a full practice session under category=SCALES so XP,
 * streak, and achievements all respond.
 */
class ScaleTrainerFragment : Fragment() {

    private val viewModel: ScaleTrainerViewModel by viewModels()

    /** Whether the user is currently flipping through categories — used to
     *  suppress the scale spinner's onItemSelected during adapter swaps. */
    private var updatingSpinners = false

    /** Scale list shown in the second spinner (filtered by category). */
    private var currentScales: List<ScaleDef> = emptyList()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_scale_trainer, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val spinnerCategory = view.findViewById<Spinner>(R.id.spinner_category)
        val spinnerScale = view.findViewById<Spinner>(R.id.spinner_scale)
        val tvName = view.findViewById<TextView>(R.id.tv_scale_name)
        val tvMeta = view.findViewById<TextView>(R.id.tv_scale_meta)
        val tvDesc = view.findViewById<TextView>(R.id.tv_scale_description)
        val tvNotesUp = view.findViewById<TextView>(R.id.tv_scale_notes_up)
        val tvNotesDown = view.findViewById<TextView>(R.id.tv_scale_notes_down)
        val tvBpm = view.findViewById<TextView>(R.id.tv_bpm_value)
        val tvTimer = view.findViewById<TextView>(R.id.tv_timer)
        val slider = view.findViewById<Slider>(R.id.slider_bpm)
        val btnBpmDown = view.findViewById<MaterialButton>(R.id.btn_bpm_down)
        val btnBpmUp = view.findViewById<MaterialButton>(R.id.btn_bpm_up)
        val btnPlay = view.findViewById<MaterialButton>(R.id.btn_play_stop)
        val btnReset = view.findViewById<MaterialButton>(R.id.btn_reset)
        val btnSave = view.findViewById<MaterialButton>(R.id.btn_save)
        val tvCount = view.findViewById<TextView>(R.id.tv_catalog_count)
        val beatDots = listOf(
            view.findViewById<View>(R.id.dot_1),
            view.findViewById<View>(R.id.dot_2),
            view.findViewById<View>(R.id.dot_3),
            view.findViewById<View>(R.id.dot_4)
        )

        tvCount.text = "${ScaleLibrary.ALL.size} scales in the library"

        // ── Category spinner ──
        val categories = ScaleCategory.values().toList()
        spinnerCategory.adapter = ArrayAdapter(
            requireContext(),
            android.R.layout.simple_spinner_dropdown_item,
            categories.map { it.label }
        )
        spinnerCategory.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val cat = categories[pos]
                currentScales = ScaleLibrary.byCategory(cat)
                updatingSpinners = true
                spinnerScale.adapter = ArrayAdapter(
                    requireContext(),
                    android.R.layout.simple_spinner_dropdown_item,
                    currentScales.map { it.name }
                )
                spinnerScale.setSelection(0)
                updatingSpinners = false
                currentScales.firstOrNull()?.let { viewModel.selectScale(it) }
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }
        // Prime with MAJOR
        spinnerCategory.setSelection(categories.indexOf(ScaleCategory.MAJOR))

        // ── Scale spinner ──
        spinnerScale.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                if (updatingSpinners) return
                val def = currentScales.getOrNull(pos) ?: return
                viewModel.selectScale(def)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        // ── BPM slider / buttons ──
        slider.addOnChangeListener { _, value, fromUser ->
            if (fromUser) viewModel.setBpm(value.toInt())
        }
        btnBpmDown.setOnClickListener {
            viewModel.setBpm(viewModel.bpm.value - 1)
        }
        btnBpmUp.setOnClickListener {
            viewModel.setBpm(viewModel.bpm.value + 1)
        }

        // ── Play / reset / save ──
        btnPlay.setOnClickListener { viewModel.toggleMetronome() }
        btnReset.setOnClickListener { viewModel.resetTimer() }
        btnSave.setOnClickListener {
            // The VM decides "1 minute minimum" etc. — just call it.
            viewModel.saveSession()
        }

        // ── Observe selected scale ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selected.collect { def ->
                if (def != null) {
                    tvName.text = def.name
                    tvMeta.text = "${def.type.category.label} · ${def.difficulty.label} · Suggested ${def.suggestedBpm} BPM"
                    tvDesc.text = def.type.description
                    tvNotesUp.text = def.ascendingNotes.joinToString("  —  ")
                    tvNotesDown.text = def.fullPattern
                        .drop(def.ascendingNotes.size)
                        .joinToString("  —  ")
                }
            }
        }

        // ── BPM display + slider sync ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.bpm.collect { b ->
                tvBpm.text = b.toString()
                if (slider.value.toInt() != b) slider.value = b.toFloat()
            }
        }

        // ── Running state ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRunning.collect { running ->
                btnPlay.text = if (running) "Stop" else "Start"
                if (!running) clearBeatDots(beatDots)
            }
        }

        // ── Timer ticker ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.elapsedMs.collect { ms ->
                tvTimer.text = formatElapsed(ms)
            }
        }

        // ── Beat flashes ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.beatState.collect { state ->
                if (state.isActive) flashBeat(beatDots, state.beatNumber - 1, state.isDownbeat)
            }
        }

        // ── Toast on events ──
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { e ->
                val msg = when (e) {
                    is ScaleTrainerViewModel.TrainerEvent.Saved ->
                        "Logged ${e.minutes} min · +${e.xpEarned} XP"
                    is ScaleTrainerViewModel.TrainerEvent.LevelUp ->
                        "⭐ Level ${e.newLevel} — ${e.title}!"
                    is ScaleTrainerViewModel.TrainerEvent.StreakAdvanced ->
                        if (e.isMilestone) "🔥 ${e.newStreak}-day streak!" else null
                    is ScaleTrainerViewModel.TrainerEvent.StreakSaved ->
                        "🛟 Streak saved at ${e.newStreak} days"
                    is ScaleTrainerViewModel.TrainerEvent.AchievementsUnlocked ->
                        "🏆 Unlocked: ${e.defs.joinToString(", ") { it.title }}"
                }
                if (msg != null) {
                    Toast.makeText(requireContext(), msg, Toast.LENGTH_SHORT).show()
                }
            }
        }
    }

    // ── Beat dot helpers ──

    private fun flashBeat(dots: List<View>, beatIdx: Int, isDownbeat: Boolean) {
        // Clear first so only one dot is lit at a time.
        clearBeatDots(dots)
        val target = dots.getOrNull(beatIdx % dots.size) ?: return
        val drawable = GradientDrawable().apply {
            shape = GradientDrawable.OVAL
            setColor(
                if (isDownbeat)
                    ContextCompat.getColor(requireContext(), R.color.antique_gold)
                else
                    ContextCompat.getColor(requireContext(), R.color.aged_ivory)
            )
        }
        target.background = drawable
        // Fade back to idle after a short flash so the dot reads as a pulse.
        target.postDelayed({
            if (isAdded) target.background =
                ContextCompat.getDrawable(requireContext(), R.drawable.bg_oak_panel)
        }, 120)
    }

    private fun clearBeatDots(dots: List<View>) {
        for (d in dots) {
            d.background = ContextCompat.getDrawable(requireContext(), R.drawable.bg_oak_panel)
        }
    }

    private fun formatElapsed(ms: Long): String {
        val totalSec = ms / 1000L
        val m = totalSec / 60L
        val s = totalSec % 60L
        return "%02d:%02d".format(m, s)
    }
}
