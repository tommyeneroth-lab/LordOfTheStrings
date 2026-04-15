package com.cellomusic.app.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.audio.playback.MidiScoreEncoder
import com.cellomusic.app.audio.playback.ScorePlayer
import com.cellomusic.app.databinding.FragmentScoreViewerBinding
import com.google.android.material.slider.Slider
import kotlinx.coroutines.launch

class ScoreViewerFragment : Fragment() {

    private var _binding: FragmentScoreViewerBinding? = null
    private val binding get() = _binding!!
    private val viewModel: ScoreViewerViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentScoreViewerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val scoreId = arguments?.getLong("scoreId") ?: return
        viewModel.loadScore(requireContext(), scoreId)

        setupPlaybackControls()
        setupEditToolbar()
        observeViewModel()

        // Tap any note to seek playback AND toggle edit selection
        binding.scoreCanvas.onNoteClicked = { measureNum, noteIdx ->
            viewModel.seekToNote(measureNum, noteIdx)
            viewModel.selectNote(measureNum, noteIdx)
        }
    }

    private fun setupPlaybackControls() {
        binding.btnPlay.setOnClickListener {
            viewModel.playWithDebugDump(requireContext())
        }

        binding.btnPause.setOnClickListener {
            viewModel.pause()
        }

        binding.btnStop.setOnClickListener {
            viewModel.stop()
            // Scroll view back to the first note after playback has fully stopped.
            // Post with a short delay so the stop() cleanup (which posts UI updates
            // via mainHandler) finishes before we trigger a new scroll animation.
            binding.scoreCanvas.postDelayed({
                binding.scoreCanvas.scrollToNote(1, 0)
            }, 100)
        }

        // Tempo speed slider — reads out in BPM (40–200).
        //
        // Two behaviors matter here:
        //  (1) Crash guard: the old implementation called setTempoMultiplier on
        //      every onChange, which triggers a full stopPlayback + re-encode +
        //      resumeFrom each time.  The Material slider fires onChange
        //      continuously during drag, so that tore down the AudioTrack /
        //      synth mid-write and could crash.  We now defer the re-encode to
        //      onStopTrackingTouch (when the user lifts their finger).
        //  (2) BPM semantics: the engine takes a multiplier relative to the
        //      score's base BPM.  We convert bpmSlider / baseBpm → multiplier
        //      just before applying.
        binding.sliderTempo.valueFrom = 20f
        binding.sliderTempo.valueTo = 200f
        binding.sliderTempo.value = 120f
        binding.sliderTempo.stepSize = 1f
        binding.sliderTempo.addOnChangeListener { _, value, _ ->
            // Live label update during drag (cheap — just a TextView assignment).
            binding.tvTempoValue.text = "${value.toInt()} BPM"
        }
        binding.sliderTempo.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
            override fun onStartTrackingTouch(slider: Slider) {}
            override fun onStopTrackingTouch(slider: Slider) {
                val bpm = slider.value
                val baseBpm = viewModel.scoreBaseBpm().coerceAtLeast(1)
                // Engine accepts 0.1×–4.0× — widest range lets 20 BPM work on
                // fast-base scores and 200 BPM on slow-base scores.
                val multiplier = (bpm / baseBpm.toFloat()).coerceIn(0.1f, 4.0f)
                viewModel.setTempoMultiplier(multiplier)
            }
        })

        // Volume slider (0..1)
        binding.sliderVolume.valueFrom = 0f
        binding.sliderVolume.valueTo = 1f
        binding.sliderVolume.value = 1f
        binding.sliderVolume.stepSize = 0.05f
        binding.sliderVolume.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setVolume(value)
                binding.tvVolumeValue.text = "%.0f%%".format(value * 100)
            }
        }

        // Export button
        binding.btnExport.setOnClickListener { showExportDialog() }

        // Transpose controls (-12 to +12 semitones)
        binding.btnTransposeDown.setOnClickListener {
            val current = viewModel.transposeSteps.value
            if (current > -12) {
                val newVal = current - 1
                viewModel.setTranspose(newVal)
                binding.tvTransposeValue.text = formatTranspose(newVal)
            }
        }
        binding.btnTransposeUp.setOnClickListener {
            val current = viewModel.transposeSteps.value
            if (current < 12) {
                val newVal = current + 1
                viewModel.setTranspose(newVal)
                binding.tvTransposeValue.text = formatTranspose(newVal)
            }
        }

        // Progress seekbar
        binding.seekbarProgress.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(sb: SeekBar, progress: Int, fromUser: Boolean) {
                if (fromUser) viewModel.seekToMeasure(progress + 1)
            }
            override fun onStartTrackingTouch(sb: SeekBar) {}
            override fun onStopTrackingTouch(sb: SeekBar) {}
        })

        // ── Practice Mode controls ───────────────────────────────────────────

        // Loop toggle
        binding.btnLoopToggle.setOnClickListener { viewModel.toggleLoop() }

        // Set loop A (start) to current measure
        binding.btnLoopStart.setOnClickListener {
            viewModel.setLoopStart()
        }

        // Set loop B (end) to current measure
        binding.btnLoopEnd.setOnClickListener {
            viewModel.setLoopEnd()
        }

        // Count-in toggle
        binding.btnCountIn.setOnClickListener { viewModel.toggleCountIn() }

        // Tempo ramp toggle
        binding.btnTempoRamp.setOnClickListener { viewModel.toggleTempoRamp() }

        // Ramp step adjustment
        binding.btnRampLess.setOnClickListener {
            val current = viewModel.tempoRampStep.value
            val newStep = (current - 0.01f).coerceAtLeast(0.01f)
            viewModel.setTempoRampStepValue(newStep)
            binding.tvRampStep.text = "+%.0f%%/pass".format(newStep * 100)
        }
        binding.btnRampMore.setOnClickListener {
            val current = viewModel.tempoRampStep.value
            val newStep = (current + 0.01f).coerceAtMost(0.25f)
            viewModel.setTempoRampStepValue(newStep)
            binding.tvRampStep.text = "+%.0f%%/pass".format(newStep * 100)
        }
    }

    private fun setupEditToolbar() {
        // Persistent save button in the header
        binding.btnSaveScore.setOnClickListener {
            viewModel.saveScore(requireContext())
            android.widget.Toast.makeText(requireContext(), "Score saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        // Fingering toggle
        binding.btnFingering.setOnClickListener { viewModel.toggleFingerings() }
        // Note/Rest edit toolbar (shown when any element is selected)
        binding.btnPitchUp.setOnClickListener { viewModel.pitchUp() }
        binding.btnPitchDown.setOnClickListener { viewModel.pitchDown() }
        binding.btnDurShorter.setOnClickListener { viewModel.durationShorter() }
        binding.btnDurLonger.setOnClickListener { viewModel.durationLonger() }
        binding.btnDeleteNote.setOnClickListener { viewModel.deleteElement() }
        binding.btnClef.setOnClickListener { showClefPicker() }
    }

    private fun showClefPicker() {
        val clefs = arrayOf("𝄢  Bass clef", "𝄡  Tenor clef", "𝄞  Treble clef")
        val types = arrayOf(
            com.cellomusic.app.domain.model.ClefType.BASS,
            com.cellomusic.app.domain.model.ClefType.TENOR,
            com.cellomusic.app.domain.model.ClefType.TREBLE
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Change clef")
            .setItems(clefs) { _, which -> viewModel.changeClef(types[which]) }
            .show()
    }

    private fun showExportDialog() {
        val options = arrayOf("🎵 Export as MIDI", "📄 Export as MusicXML", "🖨 Export as PDF")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Export Score")
            .setItems(options) { _, which ->
                val format = when (which) { 0 -> "midi"; 1 -> "musicxml"; else -> "pdf" }
                viewModel.exportScore(requireContext(), format)
            }
            .show()
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.score.collect { score ->
                score?.let {
                    binding.scoreCanvas.setScore(it)
                    binding.tvTitle.text = it.title
                    binding.tvComposer.text = it.composer ?: ""
                    val totalMeasures = it.parts.firstOrNull()?.measures?.size ?: 0
                    binding.seekbarProgress.max = (totalMeasures - 1).coerceAtLeast(0)
                    // Initialize tempo slider to this score's base BPM.
                    val baseBpm = viewModel.scoreBaseBpm().toFloat()
                        .coerceIn(binding.sliderTempo.valueFrom, binding.sliderTempo.valueTo)
                    if (binding.sliderTempo.value != baseBpm) {
                        binding.sliderTempo.value = baseBpm
                    }
                    binding.tvTempoValue.text = "${baseBpm.toInt()} BPM"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            var lastScrollMeasure = -1
            var lastScrollNote = -1
            viewModel.currentNotePosition.collect { (measureNum, noteIdx) ->
                binding.scoreCanvas.highlightNote(measureNum, noteIdx)
                binding.tvMeasureInfo.text = "Measure $measureNum"
                if (!binding.seekbarProgress.isPressed) {
                    binding.seekbarProgress.progress = (measureNum - 1).coerceAtLeast(0)
                }
                // Auto-scroll to keep the current note centred — on every note
                // change, not just measure changes, because measures can be wider
                // than the screen.
                if ((measureNum != lastScrollMeasure || noteIdx != lastScrollNote) &&
                    viewModel.playbackState.value == com.cellomusic.app.audio.playback.ScorePlayer.PlaybackState.PLAYING) {
                    lastScrollMeasure = measureNum
                    lastScrollNote = noteIdx
                    binding.scoreCanvas.scrollToNote(measureNum, noteIdx)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playbackState.collect { state ->
                when (state) {
                    ScorePlayer.PlaybackState.PLAYING -> {
                        binding.btnPlay.visibility = View.GONE
                        binding.btnPause.visibility = View.VISIBLE
                    }
                    ScorePlayer.PlaybackState.PAUSED -> {
                        binding.btnPlay.text = "▶ Resume"
                        binding.btnPlay.visibility = View.VISIBLE
                        binding.btnPause.visibility = View.GONE
                    }
                    ScorePlayer.PlaybackState.STOPPED -> {
                        binding.btnPlay.text = "▶ Play"
                        binding.btnPlay.visibility = View.VISIBLE
                        binding.btnPause.visibility = View.GONE
                    }
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.exportIntent.collect { intent ->
                intent?.let {
                    startActivity(Intent.createChooser(it, "Export score"))
                    viewModel.clearExportIntent()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedNotePos.collect { pos ->
                if (pos != null) {
                    binding.layoutEditToolbar.visibility = View.VISIBLE
                    binding.scoreCanvas.setSelectedNote(pos.first, pos.second)
                } else {
                    binding.layoutEditToolbar.visibility = View.GONE
                    binding.scoreCanvas.clearSelection()
                }
            }
        }

        // Show pitch buttons only when a Note is selected (not a Rest)
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.selectedElementType.collect { type ->
                val isNote = type == ScoreViewerViewModel.SelectedElementType.NOTE
                binding.btnPitchUp.visibility   = if (isNote) View.VISIBLE else View.GONE
                binding.btnPitchDown.visibility = if (isNote) View.VISIBLE else View.GONE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.fingeringsVisible.collect { visible ->
                binding.scoreCanvas.fingeringsVisible = visible
                binding.btnFingering.text = if (visible) "1-2-3 ✓" else "1-2-3"
            }
        }

        // ── Practice mode observers ──────────────────────────────────────────

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loopEnabled.collect { enabled ->
                val goldColor = android.graphics.Color.parseColor("#C9A84C")
                val ivoryColor = android.graphics.Color.parseColor("#F4E4C1")
                if (enabled) {
                    binding.btnLoopToggle.text = "Loop ON"
                    binding.btnLoopToggle.setTextColor(goldColor)
                    binding.btnLoopStart.visibility = android.view.View.VISIBLE
                    binding.btnLoopEnd.visibility = android.view.View.VISIBLE
                } else {
                    binding.btnLoopToggle.text = "Loop"
                    binding.btnLoopToggle.setTextColor(ivoryColor)
                    binding.btnLoopStart.visibility = android.view.View.GONE
                    binding.btnLoopEnd.visibility = android.view.View.GONE
                    binding.tvLoopInfo.text = ""
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loopStartMeasure.collect { start ->
                if (start > 0) {
                    binding.btnLoopStart.text = "A: m$start"
                    updateLoopInfo()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loopEndMeasure.collect { end ->
                if (end > 0) {
                    binding.btnLoopEnd.text = "B: m$end"
                    updateLoopInfo()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.loopPassCount.collect { count ->
                if (viewModel.loopEnabled.value && count > 0) {
                    updateLoopInfo()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.countInEnabled.collect { enabled ->
                val goldColor = android.graphics.Color.parseColor("#C9A84C")
                val ivoryColor = android.graphics.Color.parseColor("#F4E4C1")
                binding.btnCountIn.text = if (enabled) "Count-in ON" else "Count-in"
                binding.btnCountIn.setTextColor(if (enabled) goldColor else ivoryColor)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tempoRampEnabled.collect { enabled ->
                val goldColor = android.graphics.Color.parseColor("#C9A84C")
                val ivoryColor = android.graphics.Color.parseColor("#F4E4C1")
                binding.btnTempoRamp.text = if (enabled) "Tempo+ ON" else "Tempo+"
                binding.btnTempoRamp.setTextColor(if (enabled) goldColor else ivoryColor)
                val vis = if (enabled) android.view.View.VISIBLE else android.view.View.GONE
                binding.tvRampStep.visibility = vis
                binding.btnRampLess.visibility = vis
                binding.btnRampMore.visibility = vis
                if (enabled) {
                    binding.tvRampStep.text = "+%.0f%%/pass".format(viewModel.tempoRampStep.value * 100)
                }
            }
        }
    }

    private fun updateLoopInfo() {
        val start = viewModel.loopStartMeasure.value
        val end = viewModel.loopEndMeasure.value
        val passes = viewModel.loopPassCount.value
        val sb = StringBuilder()
        if (start > 0 && end > 0) {
            sb.append("m$start-$end")
            if (passes > 0) sb.append(" | Pass $passes")
        }
        binding.tvLoopInfo.text = sb.toString()
    }

    private fun formatTranspose(steps: Int): String = when {
        steps > 0 -> "+$steps st"
        steps < 0 -> "$steps st"
        else -> "0 st"
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stop()
        _binding = null
    }
}
