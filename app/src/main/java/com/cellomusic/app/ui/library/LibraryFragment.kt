package com.cellomusic.app.ui.library

import android.content.Context
import android.net.Uri
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.activity.result.contract.ActivityResultContracts
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.LinearLayoutManager
import com.cellomusic.app.R
import com.cellomusic.app.data.db.entity.ScoreEntity
import com.cellomusic.app.databinding.FragmentLibraryBinding
import com.cellomusic.app.ui.library.adapter.ScoreAdapter
import kotlinx.coroutines.launch

class LibraryFragment : Fragment() {

    private var _binding: FragmentLibraryBinding? = null
    private val binding get() = _binding!!
    private val viewModel: LibraryViewModel by viewModels()
    private lateinit var adapter: ScoreAdapter

    private val pickMusicXml = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importMusicXml(it) }
    }

    private val pickPdf = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val serverUrl = requireContext()
            .getSharedPreferences("cellomusic_prefs", Context.MODE_PRIVATE)
            .getString("omr_server_url", "") ?: ""
        if (serverUrl.isNotEmpty()) {
            viewModel.importPdfViaServer(uri, serverUrl)
        } else {
            viewModel.importPdf(uri)
        }
    }

    private val pickJpeg = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri ?: return@registerForActivityResult
        val serverUrl = requireContext()
            .getSharedPreferences("cellomusic_prefs", Context.MODE_PRIVATE)
            .getString("omr_server_url", "") ?: ""
        if (serverUrl.isNotEmpty()) {
            viewModel.importJpegViaServer(uri, serverUrl)
        } else {
            viewModel.importJpeg(uri)
        }
    }

    private val pickMidi = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importMidi(it) }
    }

    private val pickMp3 = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importMp3(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        setupToolbar()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ScoreAdapter(
            onScoreClick = { entity -> openScore(entity) },
            onScoreLongClick = { entity -> showScoreOptions(entity) }
        )
        binding.recyclerScores.apply {
            layoutManager = LinearLayoutManager(requireContext())
            adapter = this@LibraryFragment.adapter
        }
    }

    private fun setupToolbar() {
        binding.toolbar.inflateMenu(R.menu.library_menu)

        // Wire search
        val searchItem = binding.toolbar.menu.findItem(R.id.action_search)
        val searchView = searchItem?.actionView as? androidx.appcompat.widget.SearchView
        searchView?.setOnQueryTextListener(object : androidx.appcompat.widget.SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText ?: "")
                return true
            }
        })

        binding.toolbar.setOnMenuItemClickListener { item ->
            when (item.itemId) {
                R.id.action_settings -> {
                    findNavController().navigate(R.id.action_library_to_settings)
                    true
                }
                else -> false
            }
        }
    }

    private fun setupFab() {
        binding.fabImport.setOnClickListener {
            showImportDialog()
        }
        binding.fabRecord.setOnClickListener {
            when (viewModel.recordingState.value) {
                LibraryViewModel.RecordingState.IDLE -> {
                    viewModel.startRecording()
                }
                LibraryViewModel.RecordingState.RECORDING -> {
                    viewModel.stopRecordingAndTranscribe()
                }
            }
        }
    }

    private fun showImportDialog() {
        val options = arrayOf(
            "Import MusicXML",
            "Import PDF",
            "Import JPEG/Photo",
            "Import MIDI",
            "Import MP3 (audio transcription)",
            "Take Photo"
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Import Score")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMusicXml.launch("application/xml")
                    1 -> pickPdf.launch("application/pdf")
                    2 -> pickJpeg.launch("image/jpeg")
                    3 -> pickMidi.launch("*/*")
                    4 -> pickMp3.launch("audio/*")
                    5 -> openCamera()
                }
            }
            .show()
    }

    private fun openCamera() {
        findNavController().navigate(R.id.action_library_to_import)
    }

    private fun observeViewModel() {
        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.scores.collect { scores ->
                adapter.submitList(scores)
                binding.tvEmpty.visibility = if (scores.isEmpty()) View.VISIBLE else View.GONE
                binding.recyclerScores.visibility = if (scores.isEmpty()) View.GONE else View.VISIBLE
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.omrProgress.collect { progress ->
                if (progress != null) {
                    binding.omrProgressOverlay.visibility = View.VISIBLE
                    binding.tvOmrTitle.text = "Transcribing Audio…"
                    binding.tvOmrStep.text = progress
                } else if (viewModel.recordingState.value == LibraryViewModel.RecordingState.IDLE) {
                    binding.omrProgressOverlay.visibility = View.GONE
                    binding.tvOmrTitle.text = "Recognising Score…"
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.importStatus.collect { status ->
                status?.let {
                    android.widget.Toast.makeText(requireContext(), it, android.widget.Toast.LENGTH_LONG).show()
                }
            }
        }

        viewLifecycleOwner.lifecycleScope.launch {
            viewModel.recordingState.collect { state ->
                when (state) {
                    LibraryViewModel.RecordingState.RECORDING -> {
                        binding.fabRecord.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#FF5555"))
                        binding.fabRecord.setImageResource(android.R.drawable.ic_media_pause)
                        binding.tvOmrTitle.text = "Recording… tap ⏹ to stop"
                        binding.omrProgressOverlay.visibility = View.VISIBLE
                        binding.tvOmrStep.text = ""
                    }
                    LibraryViewModel.RecordingState.IDLE -> {
                        binding.fabRecord.backgroundTintList =
                            android.content.res.ColorStateList.valueOf(
                                android.graphics.Color.parseColor("#CC3333"))
                        binding.fabRecord.setImageResource(android.R.drawable.presence_audio_online)
                        // overlay is hidden by omrProgress observer when progress becomes null
                    }
                }
            }
        }
    }

    private fun openScore(entity: ScoreEntity) {
        findNavController().navigate(
            R.id.action_library_to_viewer,
            bundleOf("scoreId" to entity.id)
        )
    }

    private fun showScoreOptions(entity: ScoreEntity) {
        val options = arrayOf(
            "✏️ Rename",
            if (entity.isFavorite) "★ Remove from Favorites" else "☆ Add to Favorites",
            "🗑 Delete Score"
        )
        android.app.AlertDialog.Builder(requireContext())
            .setTitle(entity.title)
            .setItems(options) { _, which ->
                when (which) {
                    0 -> showRenameDialog(entity)
                    1 -> viewModel.toggleFavorite(entity)
                    2 -> confirmDelete(entity)
                }
            }
            .show()
    }

    private fun showRenameDialog(entity: ScoreEntity) {
        val dp = resources.displayMetrics.density
        val padding = (20 * dp).toInt()
        val paddingSmall = (8 * dp).toInt()

        val etTitle = android.widget.EditText(requireContext()).apply {
            setText(entity.title)
            selectAll()
            hint = "Score title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val etComposer = android.widget.EditText(requireContext()).apply {
            setText(entity.composer ?: "")
            hint = "Composer (optional)"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }

        val container = android.widget.LinearLayout(requireContext()).apply {
            orientation = android.widget.LinearLayout.VERTICAL
            setPadding(padding, paddingSmall, padding, paddingSmall)
            addView(android.widget.TextView(context).apply {
                text = "Title"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
            })
            addView(etTitle)
            addView(android.widget.TextView(context).apply {
                text = "Composer"
                textSize = 12f
                setTextColor(android.graphics.Color.GRAY)
                setPadding(0, paddingSmall, 0, 0)
            })
            addView(etComposer)
        }

        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Edit Score Info")
            .setView(container)
            .setPositiveButton("Save") { _, _ ->
                val newTitle = etTitle.text.toString().trim()
                val newComposer = etComposer.text.toString().trim()
                if (newTitle.isNotEmpty()) {
                    viewModel.renameScore(entity, newTitle, newComposer.ifEmpty { null })
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also {
                etTitle.postDelayed({
                    etTitle.requestFocus()
                    etTitle.setSelection(etTitle.text.length)
                    val imm = requireContext().getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(etTitle, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
                }, 100)
            }
    }

    private fun confirmDelete(entity: ScoreEntity) {
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Delete Score")
            .setMessage("Delete '${entity.title}'? This cannot be undone.")
            .setPositiveButton("Delete") { _, _ -> viewModel.deleteScore(entity) }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        viewModel.cancelRecording()
        _binding = null
    }
}
