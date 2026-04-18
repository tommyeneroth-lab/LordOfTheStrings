package com.cellomusic.app.ui.metronome

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import com.cellomusic.app.R
import com.cellomusic.app.databinding.FragmentMetronomeBinding
import kotlinx.coroutines.launch

class MetronomeFragment : Fragment() {

    private var _binding: FragmentMetronomeBinding? = null
    private val binding get() = _binding!!
    private val viewModel: MetronomeViewModel by viewModels()

    private var tapTimes = ArrayDeque<Long>(8)

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentMetronomeBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupControls()
        observeViewModel()
    }

    private fun setupControls() {
        // BPM slider 20-300
        binding.sliderBpm.valueFrom = 20f
        binding.sliderBpm.valueTo = 300f
        binding.sliderBpm.value = 120f
        binding.sliderBpm.addOnChangeListener { _, value, fromUser ->
            if (fromUser) {
                viewModel.setBpm(value.toInt())
                binding.tvBpmValue.text = value.toInt().toString()
            }
        }

        // BPM increment/decrement
        binding.btnBpmDown.setOnClickListener {
            val newBpm = (binding.sliderBpm.value - 1).coerceAtLeast(20f)
            binding.sliderBpm.value = newBpm
            viewModel.setBpm(newBpm.toInt())
        }
        binding.btnBpmUp.setOnClickListener {
            val newBpm = (binding.sliderBpm.value + 1).coerceAtMost(300f)
            binding.sliderBpm.value = newBpm
            viewModel.setBpm(newBpm.toInt())
        }

        // Play/stop
        binding.btnPlayStop.setOnClickListener {
            if (viewModel.isPlaying.value) {
                viewModel.stop()
                binding.btnPlayStop.text = "Start"
                binding.beatIndicator.stop()
            } else {
                viewModel.start()
                binding.btnPlayStop.text = "Stop"
            }
        }

        // Tap tempo
        binding.btnTapTempo.setOnClickListener {
            val now = System.currentTimeMillis()
            if (tapTimes.isNotEmpty() && now - tapTimes.last() > 3000L) {
                tapTimes.clear() // Reset if > 3 seconds gap
            }
            tapTimes.addLast(now)
            if (tapTimes.size > 8) tapTimes.removeFirst()

            if (tapTimes.size >= 2) {
                val intervals = (1 until tapTimes.size).map { tapTimes[it] - tapTimes[it - 1] }
                val medianInterval = intervals.sorted()[intervals.size / 2]
                val bpm = (60000L / medianInterval).toInt().coerceIn(20, 300)
                viewModel.setBpm(bpm)
                binding.sliderBpm.value = bpm.toFloat()
                binding.tvBpmValue.text = bpm.toString()
            }
        }

        // Time signature numerator
        binding.spinnerBeats.apply {
            val items = listOf("2", "3", "4", "5", "6", "7", "9", "12")
            val adapter = android.widget.ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, items)
            this.adapter = adapter
            setSelection(2) // default 4
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setTimeSignature(items[pos].toInt(), viewModel.denominator.value)
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        binding.spinnerDenominator.apply {
            val items = listOf("2", "4", "8", "16")
            val adapter = android.widget.ArrayAdapter(requireContext(),
                android.R.layout.simple_spinner_dropdown_item, items)
            this.adapter = adapter
            setSelection(1) // default 4
            onItemSelectedListener = object : android.widget.AdapterView.OnItemSelectedListener {
                override fun onItemSelected(parent: android.widget.AdapterView<*>?, v: View?, pos: Int, id: Long) {
                    viewModel.setTimeSignature(viewModel.numerator.value, items[pos].toInt())
                }
                override fun onNothingSelected(parent: android.widget.AdapterView<*>?) {}
            }
        }

        // Scale Trainer is a sibling practice tool. Stop the clicks before
        // we leave so this metronome doesn't keep ticking behind the
        // trainer's own embedded metronome.
        binding.btnOpenScales.setOnClickListener {
            viewModel.stop()
            binding.btnPlayStop.text = "Start"
            binding.beatIndicator.stop()
            findNavController().navigate(R.id.action_metronome_to_scale_trainer)
        }
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.beatState.collect { state ->
                if (state.isActive) {
                    binding.beatIndicator.onBeat(state, viewModel.numerator.value, viewModel.bpm.value)
                }
            }
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.stop()
        _binding = null
    }
}
