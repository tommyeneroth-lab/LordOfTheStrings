package com.cellomusic.app.ui.bow

import android.Manifest
import android.content.pm.PackageManager
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.content.ContextCompat
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.audio.bow.BowAnalyticsEngine
import com.cellomusic.app.databinding.FragmentBowAnalyticsBinding
import kotlinx.coroutines.launch
import kotlin.math.roundToInt

class BowAnalyticsFragment : Fragment() {

    private var _binding: FragmentBowAnalyticsBinding? = null
    private val binding get() = _binding!!
    private val vm: BowAnalyticsViewModel by viewModels()

    private val requestMic = registerForActivityResult(
        ActivityResultContracts.RequestPermission()
    ) { granted -> if (granted) vm.start() }

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentBowAnalyticsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        binding.btnStartStop.setOnClickListener {
            if (vm.isRunning.value) {
                vm.stop()
            } else {
                if (ContextCompat.checkSelfPermission(requireContext(), Manifest.permission.RECORD_AUDIO)
                    == PackageManager.PERMISSION_GRANTED) {
                    vm.start()
                } else {
                    requestMic.launch(Manifest.permission.RECORD_AUDIO)
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.isRunning.collect { running ->
                binding.btnStartStop.text = if (running) "■  Stop" else "▶  Start"
                binding.tvAvgQuality.visibility = if (running) View.VISIBLE else View.GONE
                if (!running) {
                    binding.tvPressureLabel.text = "Tap Start to begin"
                    binding.tvTip.text = ""
                    binding.tvToneQuality.text = "—"
                    binding.tvVolume.text = "—"
                    binding.bowPressureView.isSilent = true
                    binding.bowPressureView.pressureScore = 0f
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.metrics.collect { m ->
                m ?: return@collect
                updateUi(m)
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.bowChanges.collect { count ->
                binding.tvBowChanges.text = count.toString()
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            vm.avgToneQuality.collect { avg ->
                val pct = (avg * 100).roundToInt()
                binding.tvAvgQuality.text = "Session avg: $pct% tone quality"
            }
        }
    }

    private fun updateUi(m: BowAnalyticsEngine.BowMetrics) {
        binding.bowPressureView.isSilent = m.isSilent
        binding.bowPressureView.pressureScore = m.pressureScore

        val qualityPct = (m.toneQuality * 100).roundToInt()
        val volPct = (m.amplitude * 100 / 0.3f).coerceIn(0f, 100f).roundToInt()

        if (m.isSilent) {
            binding.tvPressureLabel.text = "🎻 Play your cello..."
            binding.tvTip.text = "Bring the bow to the string and apply gentle weight"
            binding.tvToneQuality.text = "—"
            binding.tvVolume.text = "—"
        } else {
            binding.tvToneQuality.text = "$qualityPct%"
            binding.tvVolume.text = "$volPct%"

            val (label, tip) = when {
                m.pressureScore < -0.6f -> "Under-bowed" to "Add more bow weight — let the arm sink into the string"
                m.pressureScore < -0.3f -> "Too light" to "Increase bow pressure or slow the bow speed slightly"
                m.pressureScore < -0.1f -> "Slightly light" to "Good — a touch more weight on the bow"
                m.pressureScore <= 0.1f -> "✓ Ideal bow pressure" to "Excellent — keep this arm weight and speed"
                m.pressureScore <= 0.3f -> "Slightly heavy" to "Ease the bow arm — less weight into the string"
                m.pressureScore <= 0.6f -> "Too heavy" to "Lighten the bow contact — let the bow float more"
                else                    -> "Over-bowed" to "Much less pressure — you may be hearing scratch tone"
            }
            binding.tvPressureLabel.text = label
            binding.tvTip.text = tip
        }
    }

    override fun onPause() {
        super.onPause()
        vm.stop()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
