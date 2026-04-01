package com.cellomusic.app.ui.library

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.view.*
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.widget.SearchView
import androidx.core.os.bundleOf
import androidx.fragment.app.Fragment
import androidx.fragment.app.viewModels
import androidx.lifecycle.lifecycleScope
import androidx.navigation.fragment.findNavController
import androidx.recyclerview.widget.GridLayoutManager
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
        uri?.let { viewModel.importPdf(it) }
    }

    private val pickJpeg = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importJpeg(it) }
    }

    private val pickMidi = registerForActivityResult(
        ActivityResultContracts.GetContent()
    ) { uri: Uri? ->
        uri?.let { viewModel.importMidi(it) }
    }

    override fun onCreateView(inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?): View {
        _binding = FragmentLibraryBinding.inflate(inflater, container, false)
        setHasOptionsMenu(true)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)
        setupRecyclerView()
        setupFab()
        observeViewModel()
    }

    private fun setupRecyclerView() {
        adapter = ScoreAdapter(
            onScoreClick = { entity -> openScore(entity) },
            onScoreLongClick = { entity -> showScoreOptions(entity) }
        )
        binding.recyclerScores.apply {
            layoutManager = GridLayoutManager(requireContext(), 2)
            adapter = this@LibraryFragment.adapter
        }
    }

    private fun setupFab() {
        binding.fabImport.setOnClickListener {
            showImportDialog()
        }
    }

    private fun showImportDialog() {
        val options = arrayOf("Import MusicXML", "Import PDF", "Import JPEG/Photo", "Import MIDI", "Take Photo")
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Import Score")
            .setItems(options) { _, which ->
                when (which) {
                    0 -> pickMusicXml.launch("application/xml")
                    1 -> pickPdf.launch("application/pdf")
                    2 -> pickJpeg.launch("image/jpeg")
                    3 -> pickMidi.launch("*/*")
                    4 -> openCamera()
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
                    binding.omrProgressOverlay.visibility = android.view.View.VISIBLE
                    binding.tvOmrStep.text = progress
                } else {
                    binding.omrProgressOverlay.visibility = android.view.View.GONE
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
        val editText = android.widget.EditText(requireContext()).apply {
            setText(entity.title)
            selectAll()
            hint = "Score title"
            inputType = android.text.InputType.TYPE_CLASS_TEXT or
                        android.text.InputType.TYPE_TEXT_FLAG_CAP_WORDS
        }
        val container = android.widget.FrameLayout(requireContext()).apply {
            val padding = (20 * resources.displayMetrics.density).toInt()
            setPadding(padding, 8, padding, 8)
            addView(editText)
        }
        android.app.AlertDialog.Builder(requireContext())
            .setTitle("Rename Score")
            .setView(container)
            .setPositiveButton("Rename") { _, _ ->
                val newTitle = editText.text.toString().trim()
                if (newTitle.isNotEmpty()) viewModel.renameScore(entity, newTitle)
            }
            .setNegativeButton("Cancel", null)
            .show()
            .also { dialog ->
                // Auto-show keyboard when dialog opens
                editText.postDelayed({
                    editText.requestFocus()
                    val imm = requireContext().getSystemService(
                        android.content.Context.INPUT_METHOD_SERVICE
                    ) as android.view.inputmethod.InputMethodManager
                    imm.showSoftInput(editText, android.view.inputmethod.InputMethodManager.SHOW_IMPLICIT)
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

    @Deprecated("Deprecated in Java")
    override fun onCreateOptionsMenu(menu: Menu, inflater: MenuInflater) {
        inflater.inflate(R.menu.library_menu, menu)
        val searchView = menu.findItem(R.id.action_search)?.actionView as? SearchView
        searchView?.setOnQueryTextListener(object : SearchView.OnQueryTextListener {
            override fun onQueryTextSubmit(query: String?) = true
            override fun onQueryTextChange(newText: String?): Boolean {
                viewModel.search(newText ?: "")
                return true
            }
        })
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
