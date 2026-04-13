package com.cellomusic.app.ui.journal

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.widget.AdapterView
import android.widget.ArrayAdapter
import android.widget.Spinner
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.R
import com.cellomusic.app.ui.journal.view.CalendarHeatmapView
import com.cellomusic.app.ui.journal.view.LevelProgressView
import com.cellomusic.app.ui.journal.view.TempoGraphView
import kotlinx.coroutines.launch

class StatsFragment : Fragment() {

    private val viewModel: StatsViewModel by viewModels()

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        return inflater.inflate(R.layout.fragment_stats, container, false)
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val levelView = view.findViewById<LevelProgressView>(R.id.level_progress)
        val calendarView = view.findViewById<CalendarHeatmapView>(R.id.calendar_heatmap)
        val tempoGraph = view.findViewById<TempoGraphView>(R.id.tempo_graph)
        val spinnerPiece = view.findViewById<Spinner>(R.id.spinner_tempo_piece)

        // Observe gamification profile
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.gamification.collect { profile ->
                profile?.let {
                    levelView.level = it.currentLevel
                    levelView.totalPoints = it.totalPoints
                    levelView.currentStreakDays = it.currentStreakDays
                    levelView.lifetimeMinutes = it.lifetimeMinutes
                }
            }
        }

        // Observe calendar data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.calendarData.collect { data ->
                calendarView.setData(data)
            }
        }

        // Observe tempo data
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.tempoData.collect { points ->
                tempoGraph.setData(points.map {
                    TempoGraphView.TempoPoint(it.timestampMs, it.bpm)
                })
            }
        }

        // Populate piece name spinner
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.pieceNames.collect { names ->
                val items = listOf("All pieces") + names
                spinnerPiece.adapter = ArrayAdapter(requireContext(), android.R.layout.simple_spinner_dropdown_item, items)
            }
        }

        spinnerPiece.onItemSelectedListener = object : AdapterView.OnItemSelectedListener {
            override fun onItemSelected(parent: AdapterView<*>?, v: View?, pos: Int, id: Long) {
                val selected = if (pos == 0) null else spinnerPiece.getItemAtPosition(pos) as String
                viewModel.selectPiece(selected)
            }
            override fun onNothingSelected(parent: AdapterView<*>?) {}
        }

        viewModel.loadData()
    }
}
