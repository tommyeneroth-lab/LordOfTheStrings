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
import com.cellomusic.app.data.db.entity.PracticeSessionEntity
import com.cellomusic.app.ui.journal.view.FireworksOverlayView
import com.cellomusic.app.ui.journal.view.XpBurstView
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import java.io.File
import java.io.FileOutputStream
import java.io.IOException
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

    // ── Inline-player state ─────────────────────────────────────────
    private var mediaPlayer: MediaPlayer? = null
    /** Session whose recording is currently loaded into [mediaPlayer]. */
    private var playingSession: PracticeSessionEntity? = null
    /** True once prepareAsync has completed; we block play/seek until then. */
    private var playerPrepared = false
    /** Background ticker pushing position to the adapter. */
    private var positionJob: Job? = null
    private lateinit var logAdapter: PracticeLogAdapter

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
                refreshRecordButton(recording, viewModel.pendingRecordingPath.value)
            }
        }
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pendingRecordingPath.collect { pending ->
                refreshRecordButton(viewModel.isRecording.value, pending)
            }
        }
    }

    /**
     * Three-state record button so the user always knows whether their
     * audio is going to be attached on save:
     *  • idle, no recording yet  → "🎙 Record Session Audio"
     *  • currently recording     → "⏹ Stop Rec"
     *  • stopped, waiting to save → "🎙 Recording ready — tap to re-record"
     */
    private fun refreshRecordButton(recording: Boolean, pendingPath: String?) {
        binding.btnRecord.text = when {
            recording          -> "⏹ Stop Rec"
            pendingPath != null -> "🎙 Recording ready · tap to re-record"
            else               -> "🎙 Record Session Audio"
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
        logAdapter = PracticeLogAdapter(
            onRowClick = { entry ->
                // Only rows with a recording are interactive — tapping expands
                // the inline player. Non-recording rows are inert.
                if (entry.recordingPath == null) return@PracticeLogAdapter
                val wasExpanded = logAdapter.expandedSessionId == entry.id
                val newExpanded = logAdapter.toggleExpanded(entry)
                if (wasExpanded) {
                    // Collapsing the currently-playing row — stop audio.
                    stopPlayback()
                } else if (newExpanded == entry.id) {
                    // Expanded a new row — stop any other playback but don't
                    // auto-start; let the user press ▶.
                    if (playingSession?.id != entry.id) stopPlayback()
                }
            },
            onRowLongClick = { entry ->
                if (entry.recordingPath != null) {
                    shareRecording(entry)
                    true
                } else false
            },
            onPlayToggle = { entry -> togglePlayback(entry) },
            onSeekTo = { entry, ms ->
                if (playingSession?.id == entry.id && playerPrepared) {
                    try { mediaPlayer?.seekTo(ms) } catch (_: Throwable) {}
                    pushPlaybackState()
                }
            },
            onDeleteRecording = { entry -> confirmDeleteRecording(entry) }
        )
        binding.recyclerLog.layoutManager = LinearLayoutManager(requireContext())
        binding.recyclerLog.adapter = logAdapter
        binding.recyclerLog.itemAnimator = androidx.recyclerview.widget.DefaultItemAnimator()

        // Track the freshest timestamp we've seen so we can detect a brand-new
        // session arriving from a save and scroll it into view.
        var newestSeenMs = 0L
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recentSessions.collect { sessions ->
                val incomingNewest = sessions.firstOrNull()?.timestampMs ?: 0L
                val isNewSave = incomingNewest > newestSeenMs && newestSeenMs > 0L
                logAdapter.submitList(sessions) {
                    if (isNewSave) binding.recyclerLog.scrollToPosition(0)
                }
                newestSeenMs = maxOf(newestSeenMs, incomingNewest)
            }
        }
    }

    // ── Inline recording playback ───────────────────────────────────

    private fun togglePlayback(entry: PracticeSessionEntity) {
        val path = entry.recordingPath ?: return

        // Same row → pause / resume.
        if (playingSession?.id == entry.id && mediaPlayer != null) {
            val mp = mediaPlayer ?: return
            try {
                if (mp.isPlaying) {
                    mp.pause()
                    stopPositionTicker()
                } else if (playerPrepared) {
                    mp.start()
                    startPositionTicker()
                }
            } catch (_: IllegalStateException) { /* ignore */ }
            pushPlaybackState()
            return
        }

        // Different row → tear down the old player, spin up a new one.
        releasePlayer()

        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Recording file missing", Toast.LENGTH_SHORT).show()
            viewModel.clearRecordingPath(entry)
            return
        }

        val mp = MediaPlayer()
        mediaPlayer = mp
        playingSession = entry
        playerPrepared = false
        try {
            mp.setDataSource(path)
        } catch (e: IOException) {
            Toast.makeText(requireContext(), "Can't open recording", Toast.LENGTH_SHORT).show()
            viewModel.clearRecordingPath(entry)
            releasePlayer()
            return
        }
        mp.setOnPreparedListener {
            playerPrepared = true
            mp.start()
            startPositionTicker()
            pushPlaybackState()
        }
        mp.setOnCompletionListener {
            stopPositionTicker()
            try { mp.seekTo(0) } catch (_: Throwable) {}
            pushPlaybackState()
        }
        mp.setOnErrorListener { _, _, _ ->
            Toast.makeText(requireContext(), "Playback error", Toast.LENGTH_SHORT).show()
            releasePlayer()
            pushPlaybackState()
            true
        }
        try {
            mp.prepareAsync()
        } catch (_: IllegalStateException) {
            Toast.makeText(requireContext(), "Playback error", Toast.LENGTH_SHORT).show()
            releasePlayer()
        }
        pushPlaybackState()
    }

    private fun stopPlayback() {
        releasePlayer()
        pushPlaybackState()
    }

    private fun releasePlayer() {
        stopPositionTicker()
        try { mediaPlayer?.reset() } catch (_: Throwable) {}
        try { mediaPlayer?.release() } catch (_: Throwable) {}
        mediaPlayer = null
        playingSession = null
        playerPrepared = false
    }

    private fun startPositionTicker() {
        stopPositionTicker()
        positionJob = viewLifecycleOwner.lifecycleScope.launch {
            while (isActive) {
                pushPlaybackState()
                delay(200)
            }
        }
    }

    private fun stopPositionTicker() {
        positionJob?.cancel()
        positionJob = null
    }

    private fun pushPlaybackState() {
        val session = playingSession
        val mp = mediaPlayer
        if (session == null || mp == null || !playerPrepared) {
            logAdapter.updatePlayback(session?.id, playing = false, posMs = 0, durMs = 0)
            return
        }
        val playing = try { mp.isPlaying } catch (_: IllegalStateException) { false }
        val pos = try { mp.currentPosition } catch (_: IllegalStateException) { 0 }
        val dur = try { mp.duration } catch (_: IllegalStateException) { 0 }
        logAdapter.updatePlayback(session.id, playing, pos, dur)
    }

    private fun confirmDeleteRecording(entry: PracticeSessionEntity) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete recording?")
            .setMessage("This permanently removes the audio file for this session. The session entry itself stays.")
            .setNegativeButton("Cancel", null)
            .setPositiveButton("Delete") { _, _ ->
                if (playingSession?.id == entry.id) stopPlayback()
                viewModel.deleteRecording(entry)
                logAdapter.collapse()
                Toast.makeText(requireContext(), "Recording deleted", Toast.LENGTH_SHORT).show()
            }
            .show()
    }

    private fun shareRecording(entry: PracticeSessionEntity) {
        val path = entry.recordingPath ?: return
        val file = File(path)
        if (!file.exists()) {
            Toast.makeText(requireContext(), "Recording file missing", Toast.LENGTH_SHORT).show()
            viewModel.clearRecordingPath(entry)
            return
        }
        val uri = try {
            FileProvider.getUriForFile(
                requireContext(),
                "${requireContext().packageName}.fileprovider",
                file
            )
        } catch (e: IllegalArgumentException) {
            Toast.makeText(requireContext(), "Can't share this file", Toast.LENGTH_SHORT).show()
            return
        }
        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "audio/mp4"
            putExtra(Intent.EXTRA_STREAM, uri)
            putExtra(Intent.EXTRA_SUBJECT, "Cello practice: ${entry.pieceName}")
            addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
        }
        startActivity(Intent.createChooser(intent, "Share recording"))
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
                val hostView = (parentFragment as? JournalHostFragment)?.view
                val fireworks = hostView?.findViewById<FireworksOverlayView>(
                    com.cellomusic.app.R.id.fireworks_overlay
                )
                val xpBurst = hostView?.findViewById<XpBurstView>(
                    com.cellomusic.app.R.id.xp_burst_overlay
                )
                when (event) {
                    is JournalEvent.LevelUp -> {
                        Toast.makeText(requireContext(),
                            "🎉 Level Up! You're now Level ${event.newLevel}!",
                            Toast.LENGTH_LONG).show()
                        fireworks?.fire(5)
                    }
                    is JournalEvent.GoalCompleted -> {
                        Toast.makeText(requireContext(),
                            "🎆 Goal completed! +1000 bonus points!",
                            Toast.LENGTH_LONG).show()
                        fireworks?.fire(4)
                    }
                    is JournalEvent.StreakSaved -> {
                        // Grace day kicked in — make it clearly visible so the
                        // user knows the streak-saver feature exists.
                        Toast.makeText(requireContext(),
                            "🔥 Streak saved! ${event.newStreak} days and counting.",
                            Toast.LENGTH_LONG).show()
                    }
                    is JournalEvent.StreakAdvanced -> {
                        if (event.isMilestone) {
                            Toast.makeText(requireContext(),
                                "🔥 ${event.newStreak}-day streak milestone!",
                                Toast.LENGTH_LONG).show()
                            fireworks?.fire(3)
                        }
                    }
                    is JournalEvent.AchievementsUnlocked -> {
                        val first = event.defs.first()
                        val more = if (event.defs.size > 1)
                            " (+${event.defs.size - 1} more)" else ""
                        Toast.makeText(requireContext(),
                            "🏆 Achievement unlocked: ${first.icon} ${first.title}$more",
                            Toast.LENGTH_LONG).show()
                        fireworks?.fire(2)
                    }
                    is JournalEvent.SessionSaved -> {
                        // Celebrate every save with the XP burst. Don't
                        // duplicate a toast — the burst IS the feedback.
                        xpBurst?.show(event.xpEarned, event.subText)
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
        // Don't keep audio playing while the user is elsewhere in the app.
        releasePlayer()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        handler.removeCallbacks(tickRunnable)
        releasePlayer()
        _binding = null
    }
}
