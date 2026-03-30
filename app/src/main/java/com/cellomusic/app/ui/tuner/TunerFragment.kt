package com.cellomusic.app.ui.tuner

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
import com.cellomusic.app.audio.tuner.HpsProcessor
import com.cellomusic.app.databinding.FragmentTunerBinding
import kotlinx.coroutines.launch
import kotlin.math.PI
import kotlin.math.sin

class TunerFragment : Fragment() {

    private var _binding: FragmentTunerBinding? = null
    private val binding get() = _binding!!

    private val viewModel: TunerViewModel by viewModels()
    private var referenceToneTrack: AudioTrack? = null

    private val requestPermission = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted ->
        if (granted) viewModel.startTuner()
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentTunerBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        setupStringButtons()
        observeViewModel()
    }

    private fun setupStringButtons() {
        binding.btnStringC.setOnClickListener { playReferenceTone(65.41f) }
        binding.btnStringG.setOnClickListener { playReferenceTone(98.0f) }
        binding.btnStringD.setOnClickListener { playReferenceTone(146.83f) }
        binding.btnStringA.setOnClickListener { playReferenceTone(220.0f) }

        binding.btnStartStop.setOnClickListener {
            if (viewModel.isRunning.value) {
                viewModel.stopTuner()
                binding.btnStartStop.text = "Start Tuner"
            } else {
                checkPermissionAndStart()
            }
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pitchResult.collect { result ->
                if (result != null) {
                    binding.tunerGauge.update(result.frequency, result.centsOffset, result.closestString)
                    binding.tvNoteDetected.text = result.noteName
                    binding.tvFrequency.text = "%.2f Hz".format(result.frequency)
                    binding.tvTuningStatus.text = result.tuningDescription
                    binding.tvTuningStatus.setTextColor(
                        if (result.isInTune) 0xFF00AA00.toInt() else 0xFFCC0000.toInt()
                    )
                } else {
                    binding.tunerGauge.reset()
                    binding.tvNoteDetected.text = "—"
                    binding.tvFrequency.text = "Listening..."
                    binding.tvTuningStatus.text = ""
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.isRunning.collect { running ->
                binding.btnStartStop.text = if (running) "Stop Tuner" else "Start Tuner"
            }
        }
    }

    private fun checkPermissionAndStart() {
        if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
            == PackageManager.PERMISSION_GRANTED) {
            viewModel.startTuner()
        } else {
            requestPermission.launch(Manifest.permission.RECORD_AUDIO)
        }
    }

    private fun playReferenceTone(frequency: Float) {
        referenceToneTrack?.stop()
        referenceToneTrack?.release()

        val sampleRate = 44100
        val durationSec = 2
        val numSamples = sampleRate * durationSec
        val pcm = ShortArray(numSamples)

        // Generate sine wave with fade in/out
        for (i in pcm.indices) {
            val t = i.toFloat() / sampleRate
            val fadeIn = if (i < sampleRate / 10) i.toFloat() / (sampleRate / 10) else 1f
            val fadeOut = if (i > numSamples - sampleRate / 10)
                (numSamples - i).toFloat() / (sampleRate / 10) else 1f
            pcm[i] = (sin(2 * PI * frequency * t) * Short.MAX_VALUE * 0.7f * fadeIn * fadeOut).toInt().toShort()
        }

        val track = AudioTrack.Builder()
            .setAudioAttributes(
                AudioAttributes.Builder()
                    .setUsage(AudioAttributes.USAGE_MEDIA)
                    .build()
            )
            .setAudioFormat(
                AudioFormat.Builder()
                    .setSampleRate(sampleRate)
                    .setEncoding(AudioFormat.ENCODING_PCM_16BIT)
                    .setChannelMask(AudioFormat.CHANNEL_OUT_MONO)
                    .build()
            )
            .setBufferSizeInBytes(pcm.size * 2)
            .setTransferMode(AudioTrack.MODE_STATIC)
            .build()

        track.write(pcm, 0, pcm.size)
        track.play()
        referenceToneTrack = track
    }

    override fun onResume() {
        super.onResume()
        checkPermissionAndStart()
    }

    override fun onPause() {
        super.onPause()
        viewModel.stopTuner()
        referenceToneTrack?.stop()
        referenceToneTrack?.release()
        referenceToneTrack = null
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
