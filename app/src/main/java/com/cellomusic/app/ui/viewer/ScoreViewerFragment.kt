package com.cellomusic.app.ui.viewer

import android.content.Intent
import android.os.Bundle
import android.view.*
import android.widget.SeekBar
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.audio.playback.ScorePlayer
import com.cellomusic.app.databinding.FragmentScoreViewerBinding
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
            when (viewModel.playbackState.value) {
                ScorePlayer.PlaybackState.PLAYING -> viewModel.pause()
                ScorePlayer.PlaybackState.PAUSED, ScorePlayer.PlaybackState.STOPPED -> viewModel.play()
            }
        }

        binding.btnStop.setOnClickListener {
            viewModel.stop()
        }

        // Tempo speed slider (0.25x to 2.0x)
        binding.sliderTempo.valueFrom = 0.25f
        binding.sliderTempo.valueTo = 2.0f
        binding.sliderTempo.value = 1.0f
        binding.sliderTempo.stepSize = 0.05f
        binding.sliderTempo.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setTempoMultiplier(value)
                binding.tvTempoValue.text = "%.0f%%".format(value * 100)
            }
        }

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
    }

    private fun setupEditToolbar() {
        // Persistent save button in the header
        binding.btnSaveScore.setOnClickListener {
            viewModel.saveScore(requireContext())
            android.widget.Toast.makeText(requireContext(), "Score saved", android.widget.Toast.LENGTH_SHORT).show()
        }
        // Note edit toolbar (shown when a note is selected)
        binding.btnPitchUp.setOnClickListener { viewModel.pitchUp() }
        binding.btnPitchDown.setOnClickListener { viewModel.pitchDown() }
        binding.btnDurShorter.setOnClickListener { viewModel.durationShorter() }
        binding.btnDurLonger.setOnClickListener { viewModel.durationLonger() }
        binding.btnDeleteNote.setOnClickListener { viewModel.deleteNote() }
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
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.currentNotePosition.collect { (measureNum, noteIdx) ->
                binding.scoreCanvas.highlightNote(measureNum, noteIdx)
                binding.tvMeasureInfo.text = "Measure $measureNum"
                if (!binding.seekbarProgress.isPressed) {
                    binding.seekbarProgress.progress = (measureNum - 1).coerceAtLeast(0)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.playbackState.collect { state ->
                binding.btnPlay.text = when (state) {
                    ScorePlayer.PlaybackState.PLAYING -> "⏸ Pause"
                    ScorePlayer.PlaybackState.PAUSED -> "▶ Resume"
                    ScorePlayer.PlaybackState.STOPPED -> "▶ Play"
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
