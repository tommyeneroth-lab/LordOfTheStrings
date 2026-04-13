package com.cellomusic.app.ui.journal

import android.Manifest
import android.content.Intent
import android.content.pm.PackageManager
import android.graphics.Color
import android.graphics.Paint
import android.graphics.pdf.PdfDocument
import android.media.MediaPlayer
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.ArrayAdapter
import android.widget.Spinner
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.recyclerview.widget.LinearLayoutManager
import com.cellomusic.app.databinding.FragmentPracticeJournalBinding
import com.cellomusic.app.ui.journal.view.FireworksOverlayView
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.text.SimpleDateFormat
import java.util.*

class PracticeJournalFragment : Fragment() {

    private var _binding: FragmentPracticeJournalBinding? = null
    private val binding get() = _binding!!
    private val viewModel: PracticeJournalViewModel by viewModels()

    private val handler = Handler(Looper.getMainLooper())
    private var timerRunning = false
    private var sessionStartMs = 0L
    private var elapsedMs = 0L
    private var selectedEval = 0
    private var selectedStrainLevel = 0

    private val categories = arrayOf(
        "General", "Intonation", "Vibrato", "Bow Technique",
        "Memorization", "Scales", "Sight-reading"
    )
    private val categoryValues = arrayOf(
        "", "INTONATION", "VIBRATO", "BOW_TECHNIQUE",
        "MEMORIZATION", "SCALES", "SIGHT_READING"
    )

    private val strainAreas = arrayOf("", "NECK", "LEFT_HAND", "RIGHT_HAND", "BACK", "SHOULDER")
    private val strainAreaLabels = arrayOf("None", "Neck", "Left Hand", "Right Hand", "Back", "Shoulder")

    private var mediaPlayer: MediaPlayer? = null

    private val tickRunnable = object : Runnable {
        override fun run() {
            if (!timerRunning) return
            elapsedMs = System.currentTimeMillis() - sessionStartMs
            binding.tvSessionTime.text = formatTime(elapsedMs)
            handler.postDelayed(this, 500)
        }
    }

    private val micPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startRecording()
        else Toast.makeText(requireContext(), "Microphone permission required", Toast.LENGTH_SHORT).show()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentPracticeJournalBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupCategorySpinner()
        setupTimer()
        setupRecording()
        setupEvalButtons()
        setupStrainButtons()
        setupActions()
        setupRecycler()
        observeViewModel()
    }

    private fun setupCategorySpinner() {
        val adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, categories)
        binding.spinnerCategory.adapter = adapter
    }

    private fun setupTimer() {
        binding.btnTimerStart.setOnClickListener {
            if (!timerRunning) {
                timerRunning = true
                sessionStartMs = System.currentTimeMillis() - elapsedMs
                handler.post(tickRunnable)
                binding.btnTimerStart.text = "Pause"
                val piece = binding.etPieceName.text.toString().trim()
                binding.tvPieceName.text = if (piece.isNotEmpty()) piece else "Free practice"
            } else {
                timerRunning = false
                handler.removeCallbacks(tickRunnable)
                binding.btnTimerStart.text = "Resume"
            }
        }
        binding.btnTimerStop.setOnClickListener {
            timerRunning = false
            handler.removeCallbacks(tickRunnable)
            binding.btnTimerStart.text = "Start"
        }
    }

    private fun setupRecording() {
        binding.btnRecord.setOnClickListener {
            if (viewModel.isRecording.value) {
                viewModel.stopRecording()
            } else {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    viewModel.startRecording()
                } else {
                    micPermission.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRecording.collect { recording ->
                binding.btnRecord.text = if (recording) "⏹ Stop Rec" else "🎙 Record"
            }
        }
    }

    private fun setupEvalButtons() {
        val evalButtons = listOf(
            binding.btnEval1 to 1,
            binding.btnEval2 to 2,
            binding.btnEval3 to 3,
            binding.btnEval4 to 4,
            binding.btnEval5 to 5
        )
        evalButtons.forEach { (btn, score) ->
            btn.setOnClickListener {
                selectedEval = score
                evalButtons.forEach { (b, _) ->
                    b.isSelected = (b == btn)
                    b.strokeWidth = if (b == btn) 3 else 1
                }
            }
        }
    }

    private fun setupStrainButtons() {
        val buttons = listOf(
            binding.btnStrain0 to 0,
            binding.btnStrain1 to 1,
            binding.btnStrain2 to 2,
            binding.btnStrain3 to 3,
            binding.btnStrain4 to 4,
            binding.btnStrain5 to 5
        )
        buttons.forEach { (btn, level) ->
            btn.setOnClickListener {
                selectedStrainLevel = level
                buttons.forEach { (b, _) ->
                    b.isSelected = (b == btn)
                    b.strokeWidth = if (b == btn) 3 else 1
                }
            }
        }
    }

    private fun setupActions() {
        binding.btnSaveEntry.setOnClickListener {
            val piece = binding.etPieceName.text.toString().trim().ifEmpty { "Free practice" }
            val notes = binding.etSessionNotes.text.toString().trim()
            val challenge = binding.etChallenge.text.toString().trim()
            val nextTime = binding.etNextTime.text.toString().trim()
            val durationMin = (elapsedMs / 60_000).toInt().coerceAtLeast(1)
            val catIdx = binding.spinnerCategory.selectedItemPosition
            val category = categoryValues.getOrElse(catIdx) { "" }
            val strainArea = if (selectedStrainLevel > 0) {
                strainAreas.getOrElse(binding.spinnerStrainArea.selectedItemPosition) { "" }
            } else ""

            viewModel.saveSession(
                pieceName = piece,
                notes = notes,
                durationMin = durationMin,
                selfEval = selectedEval,
                challenge = challenge,
                nextTimeNote = nextTime,
                category = category,
                strainLevel = selectedStrainLevel,
                strainArea = strainArea
            )

            // Reset form
            elapsedMs = 0L
            binding.tvSessionTime.text = "00:00"
            binding.etSessionNotes.text?.clear()
            binding.etChallenge.text?.clear()
            binding.etNextTime.text?.clear()
            binding.tvPieceName.text = "No piece selected"
            selectedEval = 0
            selectedStrainLevel = 0
        }

        binding.btnExportPdf.setOnClickListener { exportToPdf() }
    }

    private fun setupRecycler() {
        val adapter = PracticeLogAdapter()
        binding.recyclerLog.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLog.adapter = adapter
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentSessions.collect { sessions ->
                adapter.submitList(sessions)
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gamification.collect { profile ->
                profile?.let {
                    binding.tvLevel.text = "Level ${it.currentLevel}"
                    binding.tvPoints.text = "${it.totalPoints} pts"
                    binding.tvStreak.text = "🔥 ${it.currentStreakDays} day streak"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.events.collect { event ->
                when (event) {
                    is JournalEvent.LevelUp -> {
                        Toast.makeText(requireContext(), "🎉 Level Up! You're now Level ${event.newLevel}!", Toast.LENGTH_LONG).show()
                        // Trigger fireworks in parent host
                        (parentFragment as? JournalHostFragment)?.view
                            ?.findViewById<FireworksOverlayView>(com.cellomusic.app.R.id.fireworks_overlay)
                            ?.fire(5)
                    }
                    is JournalEvent.GoalCompleted -> {
                        Toast.makeText(requireContext(), "🎆 Goal completed! +1000 bonus points!", Toast.LENGTH_LONG).show()
                        (parentFragment as? JournalHostFragment)?.view
                            ?.findViewById<FireworksOverlayView>(com.cellomusic.app.R.id.fireworks_overlay)
                            ?.fire(4)
                    }
                    is JournalEvent.SessionSaved -> {
                        Toast.makeText(requireContext(), "Session saved!", Toast.LENGTH_SHORT).show()
                    }
                }
            }
        }
    }

    private fun formatTime(ms: Long): String {
        val sec = (ms / 1000) % 60
        val min = (ms / 60_000) % 60
        val hr  = ms / 3_600_000
        return if (hr > 0) "%02d:%02d:%02d".format(hr, min, sec)
        else            "%02d:%02d".format(min, sec)
    }

    private fun exportToPdf() {
        val entries = viewModel.recentSessions.value
        if (entries.isEmpty()) {
            Toast.makeText(requireContext(), "No entries to export", Toast.LENGTH_SHORT).show()
            return
        }

        val doc = PdfDocument()
        val pageInfo = PdfDocument.PageInfo.Builder(595, 842, 1).create()
        val page = doc.startPage(pageInfo)
        val canvas = page.canvas

        val titlePaint = Paint().apply { color = Color.BLACK; textSize = 20f; isFakeBoldText = true }
        val bodyPaint  = Paint().apply { color = Color.DKGRAY; textSize = 12f }
        val dateFormat = SimpleDateFormat("dd MMM yyyy HH:mm", Locale.getDefault())

        canvas.drawText("Practice Journal", 40f, 60f, titlePaint)
        canvas.drawText("Generated: ${dateFormat.format(Date())}", 40f, 82f, bodyPaint)

        var y = 110f
        for (entry in entries.take(30)) {
            if (y > 800f) break
            canvas.drawText("${dateFormat.format(Date(entry.timestampMs))}  —  ${entry.pieceName}  (${entry.durationMin} min)", 40f, y, bodyPaint)
            y += 16f
            if (entry.notes.isNotEmpty()) {
                canvas.drawText("  ${entry.notes.take(90)}", 40f, y, bodyPaint)
                y += 16f
            }
            if (entry.challenge.isNotEmpty()) {
                canvas.drawText("  Challenge: ${entry.challenge.take(80)}", 40f, y, bodyPaint)
                y += 16f
            }
            val evalStr = when (entry.selfEval) { 1 -> "1/5"; 2 -> "2/5"; 3 -> "3/5"; 4 -> "4/5"; else -> "5/5" }
            canvas.drawText("  Rating: $evalStr   Points: ${entry.totalPoints}", 40f, y, bodyPaint)
            y += 22f
        }

        doc.finishPage(page)
        val file = File(requireContext().cacheDir, "practice_journal.pdf")
        FileOutputStream(file).use { doc.writeTo(it) }
        doc.close()

        val uri = FileProvider.getUriForFile(requireContext(), "${requireContext().packageName}.fileprovider", file)
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "application/pdf"
            putExtra(Intent.EXTRA_STREAM, uri)
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Export Journal PDF"))
    }

    override fun onPause() {
        super.onPause()
        if (timerRunning) {
            timerRunning = false
            handler.removeCallbacks(tickRunnable)
            binding.btnTimerStart.text = "Resume"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(tickRunnable)
        mediaPlayer?.release()
        _binding = null
    }
}
