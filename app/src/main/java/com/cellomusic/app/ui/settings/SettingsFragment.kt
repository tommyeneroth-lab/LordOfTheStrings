package com.cellomusic.app.ui.settings

import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.databinding.FragmentSettingsBinding
import com.cellomusic.app.omr.OmrServerClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

class SettingsFragment : Fragment() {

    private var _binding: FragmentSettingsBinding? = null
    private val binding get() = _binding!!

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?, savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSettingsBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        val prefs = requireContext().getSharedPreferences("cellomusic_prefs", Context.MODE_PRIVATE)
        val savedUrl = prefs.getString("omr_server_url", "") ?: ""

        binding.etServerUrl.setText(savedUrl)
        updateStatusIndicator(savedUrl)

        binding.btnSaveUrl.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            prefs.edit().putString("omr_server_url", url).apply()
            updateStatusIndicator(url)
            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.setTextColor(Color.parseColor("#B5A05A"))
            binding.tvTestResult.text = "URL saved."
        }

        binding.btnTestServer.setOnClickListener {
            val url = binding.etServerUrl.text.toString().trim()
            if (url.isEmpty()) {
                binding.tvTestResult.visibility = View.VISIBLE
                binding.tvTestResult.setTextColor(Color.parseColor("#B5A05A"))
                binding.tvTestResult.text = "Enter a server URL first."
                return@setOnClickListener
            }
            binding.btnTestServer.isEnabled = false
            binding.tvTestResult.visibility = View.VISIBLE
            binding.tvTestResult.setTextColor(Color.parseColor("#B5A05A"))
            binding.tvTestResult.text = "Testing connection…"

            viewLifecycleOwner.lifecycleScope.launch {
                val ok = withContext(Dispatchers.IO) { OmrServerClient.checkHealth(url) }
                if (_binding == null) return@launch
                if (ok) {
                    binding.tvTestResult.setTextColor(Color.parseColor("#5DB86A"))
                    binding.tvTestResult.text = "Connected — server is healthy."
                    binding.tvStatusDot.setTextColor(Color.parseColor("#5DB86A"))
                    binding.tvStatusLabel.text = "Server connected"
                } else {
                    binding.tvTestResult.setTextColor(Color.parseColor("#C0504D"))
                    binding.tvTestResult.text = "Could not reach server. Check the URL and tunnel."
                }
                binding.btnTestServer.isEnabled = true
            }
        }
    }

    private fun updateStatusIndicator(url: String) {
        if (url.isEmpty()) {
            binding.tvStatusDot.setTextColor(Color.parseColor("#888888"))
            binding.tvStatusLabel.text = "No server configured — using on-device OMR"
        } else {
            binding.tvStatusDot.setTextColor(Color.parseColor("#B5A05A"))
            binding.tvStatusLabel.text = "Server URL set — tap Test Connection to verify"
        }
    }

    override fun onDestroyView() {
        super.onDestroyView()
        _binding = null
    }
}
