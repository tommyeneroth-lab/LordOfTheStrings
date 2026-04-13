package com.cellomusic.app.ui.decibel

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioFormat
import android.media.AudioRecord
import android.media.MediaRecorder
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.databinding.FragmentDecibelMeterBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.log10
import kotlin.math.sqrt

class DecibelMeterFragment : Fragment() {

    private var _binding: FragmentDecibelMeterBinding? = null
    private val binding get() = _binding!!

    private var audioRecord: AudioRecord? = null
    private var measureJob: Job? = null
    private var isRunning = false
    private var peakDb = Float.MIN_VALUE
    private var sumDb = 0.0
    private var sampleCount = 0

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startMeasuring() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDecibelMeterBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStartStop.setOnClickListener {
            if (isRunning) stopMeasuring() else checkAndStart()
        }
        binding.btnResetPeak.setOnClickListener {
            peakDb = Float.MIN_VALUE
            sumDb = 0.0; sampleCount = 0
            binding.tvPeakDb.text = "— dB"
            binding.tvAvgDb.text = "— dB"
        }
    }

    private fun checkAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startMeasuring()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun startMeasuring() {
        val sampleRate = 44100
        val bufSize = AudioRecord.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT)
            .coerceAtLeast(4096)

        val record = AudioRecord(MediaRecorder.AudioSource.MIC, sampleRate,
            AudioFormat.CHANNEL_IN_MONO, AudioFormat.ENCODING_PCM_FLOAT, bufSize * 2)
        if (record.state != AudioRecord.STATE_INITIALIZED) return

        audioRecord = record
        isRunning = true
        binding.btnStartStop.text = "Stop Meter"
        record.startRecording()

        measureJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.IO) {
            val buf = FloatArray(bufSize / 4)
            while (isRunning) {
                val read = record.read(buf, 0, buf.size, AudioRecord.READ_BLOCKING)
                if (read <= 0) continue

                // RMS → dBSPL (approximate)
                val rms = sqrt(buf.take(read).map { it * it.toDouble() }.average()).toFloat()
                val db = if (rms > 1e-7f) (20f * log10(rms) + 90f).coerceIn(0f, 120f) else 0f

                if (db > peakDb) peakDb = db
                sumDb += db; sampleCount++
                val avgDb = (sumDb / sampleCount).toFloat()

                withContext(Dispatchers.Main) {
                    if (_binding == null) return@withContext
                    binding.tvDbValue.text = "%.1f dB".format(db)
                    binding.tvPeakDb.text = "%.1f dB".format(peakDb)
                    binding.tvAvgDb.text = "%.1f dB".format(avgDb)
                    binding.decibelBar.setLevel(db)

                    val (status, color) = when {
                        db < 55f  -> "Very quiet" to 0xFF4CAF50.toInt()
                        db < 70f  -> "Comfortable" to 0xFF4CAF50.toInt()
                        db < 80f  -> "Moderate — OK for practice" to 0xFFF57F17.toInt()
                        db < 85f  -> "Loud — take breaks" to 0xFFFF8F00.toInt()
                        db < 95f  -> "Too loud! Protect your hearing" to 0xFFC62828.toInt()
                        else      -> "DANGER — stop immediately" to 0xFFB00020.toInt()
                    }
                    binding.tvDbStatus.text = status
                    binding.tvDbValue.setTextColor(color)
                }
            }
        }
    }

    private fun stopMeasuring() {
        isRunning = false
        measureJob?.cancel()
        audioRecord?.stop()
        audioRecord?.release()
        audioRecord = null
        binding.btnStartStop.text = "Start Meter"
        binding.tvDbValue.text = "— dB"
        binding.tvDbStatus.text = "Start measuring"
        binding.decibelBar.setLevel(0f)
    }

    override fun onPause() {
        super.onPause()
        if (isRunning) stopMeasuring()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMeasuring()
        _binding = null
    }
}
