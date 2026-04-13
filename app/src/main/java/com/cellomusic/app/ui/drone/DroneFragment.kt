package com.cellomusic.app.ui.drone

import android.Manifest
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioTrack
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.cellomusic.app.R
import com.cellomusic.app.audio.tuner.TunerEngine
import com.cellomusic.app.databinding.FragmentDroneBinding
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.sin

class DroneFragment : Fragment() {

    private var _binding: FragmentDroneBinding? = null
    private val binding get() = _binding!!
    private val viewModel: DroneViewModel by viewModels()

    private var droneTrack: AudioTrack? = null
    private var droneFreq = 65.41
    private var droneVolume = 0.7f
    private var droneRunning = false

    private val tunerEngine = TunerEngine()
    private var tunerJob: Job? = null
    private var steadyStart = 0L

    private val requestMicPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) startTunerFeedback() }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentDroneBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupDroneButtons()
        setupSlider()
        binding.btnOpenDbMeter.setOnClickListener {
            findNavController().navigate(R.id.action_drone_to_decibel)
        }
        binding.btnStartDrone.setOnClickListener {
            if (droneRunning) stopDrone() else startDrone()
        }
    }

    private fun setupDroneButtons() {
        val buttons = listOf(
            binding.btnDroneC to 65.41,
            binding.btnDroneG to 97.999,
            binding.btnDroneD to 146.832,
            binding.btnDroneA to 220.0
        )
        buttons.forEach { (btn, freq) ->
            btn.setOnClickListener {
                droneFreq = freq
                viewModel.setDroneNote(btn.text.toString())
                if (droneRunning) restartDrone()
            }
        }
    }

    private fun setupSlider() {
        binding.sliderDroneVolume.addOnChangeListener { _, value, _ ->
            droneVolume = value / 100f
            droneTrack?.setVolume(droneVolume)
        }
    }

    private fun startDrone() {
        droneRunning = true
        binding.btnStartDrone.text = "Stop Drone"
        playDroneTone(droneFreq)
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            startTunerFeedback()
        } else {
            requestMicPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun stopDrone() {
        droneRunning = false
        binding.btnStartDrone.text = "Start Drone"
        droneTrack?.stop(); droneTrack?.release(); droneTrack = null
        tunerEngine.stop(); tunerJob?.cancel()
        binding.tvSmiley.text = "😐"
        binding.tvIntonationStatus.text = "Start drone and play a note"
        binding.tvHoldTimer.text = "Hold: 0.0s"
        binding.tunerGauge.reset()
    }

    private fun restartDrone() {
        droneTrack?.stop(); droneTrack?.release(); droneTrack = null
        playDroneTone(droneFreq)
    }

    private fun playDroneTone(freq: Double) {
        val sampleRate = 44100
        val bufSize = AudioTrack.getMinBufferSize(sampleRate,
            AudioFormat.CHANNEL_OUT_MONO, AudioFormat.ENCODING_PCM_FLOAT)
        val track = AudioTrack.Builder()
            .setAudioAttributes(AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
                .setContentType(AudioAttributes.CONTENT_TYPE_MUSIC).build())
            .setAudioFormat(AudioFormat.Builder()
                .setSampleRate(sampleRate)
                .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)
                .setChannelMask(AudioFormat.CHANNEL_OUT_MONO).build())
            .setBufferSizeInBytes(bufSize * 4)
            .setTransferMode(AudioTrack.MODE_STREAM).build()
        track.setVolume(droneVolume)
        track.play()
        droneTrack = track

        lifecycleScope.launch(Dispatchers.IO) {
            val chunk = FloatArray(bufSize / 4)
            var phase = 0.0
            val omega = 2.0 * PI * freq / sampleRate
            while (droneRunning && _binding != null) {
                for (i in chunk.indices) {
                    // Cello-like overtone series
                    chunk[i] = (sin(phase)      * 0.45 +
                                sin(2 * phase)  * 0.25 +
                                sin(3 * phase)  * 0.15 +
                                sin(4 * phase)  * 0.08 +
                                sin(5 * phase)  * 0.04 +
                                sin(6 * phase)  * 0.02 +
                                sin(7 * phase)  * 0.01).toFloat()
                    phase += omega
                    if (phase > 2.0 * PI) phase -= 2.0 * PI
                }
                track.write(chunk, 0, chunk.size, AudioTrack.WRITE_BLOCKING)
            }
        }
    }

    private fun startTunerFeedback() {
        tunerJob = viewLifecycleOwner.lifecycleScope.launch(Dispatchers.Default) {
            tunerEngine.pitchFlow().collect { result ->
                result ?: return@collect
                val absCents = abs(result.centsOffset)
                val (smiley, status) = when {
                    absCents <= 5f  -> "😄" to "Excellent intonation!"
                    absCents <= 12f -> "🙂" to "Very good — almost there"
                    absCents <= 20f -> "😐" to "Close — adjust slightly"
                    absCents <= 35f -> "😕" to "A bit off"
                    else            -> "😬" to "Quite far — listen carefully"
                }
                val now = System.currentTimeMillis()
                if (absCents <= 10f) {
                    if (steadyStart == 0L) steadyStart = now
                } else {
                    steadyStart = 0L
                }
                val holdSec = if (steadyStart > 0L) (now - steadyStart) / 1000f else 0f

                requireActivity().runOnUiThread {
                    if (_binding == null) return@runOnUiThread
                    binding.tunerGauge.update(result.frequency, result.centsOffset, result.closestString)
                    binding.tvSmiley.text = smiley
                    binding.tvIntonationStatus.text = status
                    binding.tvHoldTimer.text = "Hold: %.1fs".format(holdSec)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        if (droneRunning) stopDrone()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopDrone()
        _binding = null
    }
}
