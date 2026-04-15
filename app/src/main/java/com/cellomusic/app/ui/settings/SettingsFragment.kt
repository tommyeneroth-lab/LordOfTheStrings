package com.cellomusic.app.ui.settings

import android.app.TimePickerDialog
import android.content.Context
import android.graphics.Color
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.cellomusic.app.databinding.FragmentSettingsBinding
import com.cellomusic.app.notification.PracticeReminderScheduler
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

        // ── Practice Reminders ───────────────────────────────────────────────
        setupReminderControls()
    }

    private fun setupReminderControls() {
        val ctx = requireContext()

        // Load saved state
        val isEnabled = PracticeReminderScheduler.isEnabled(ctx)
        val hour = PracticeReminderScheduler.getHour(ctx)
        val minute = PracticeReminderScheduler.getMinute(ctx)

        binding.switchReminder.isChecked = isEnabled
        binding.btnPickTime.text = formatTime(hour, minute)
        updateReminderStatus(isEnabled, hour, minute)

        binding.switchReminder.setOnCheckedChangeListener { _, checked ->
            val h = PracticeReminderScheduler.getHour(ctx)
            val m = PracticeReminderScheduler.getMinute(ctx)
            if (checked) {
                PracticeReminderScheduler.schedule(ctx, h, m)
                updateReminderStatus(true, h, m)
            } else {
                PracticeReminderScheduler.cancel(ctx)
                updateReminderStatus(false, h, m)
            }
        }

        binding.btnPickTime.setOnClickListener {
            val currentHour = PracticeReminderScheduler.getHour(ctx)
            val currentMinute = PracticeReminderScheduler.getMinute(ctx)

            TimePickerDialog(ctx, { _, selectedHour, selectedMinute ->
                binding.btnPickTime.text = formatTime(selectedHour, selectedMinute)
                if (binding.switchReminder.isChecked) {
                    PracticeReminderScheduler.schedule(ctx, selectedHour, selectedMinute)
                } else {
                    // Save time even if not enabled
                    val prefs = ctx.getSharedPreferences("cellomusic_prefs", Context.MODE_PRIVATE)
                    prefs.edit()
                        .putInt("reminder_hour", selectedHour)
                        .putInt("reminder_minute", selectedMinute)
                        .apply()
                }
                updateReminderStatus(binding.switchReminder.isChecked, selectedHour, selectedMinute)
            }, currentHour, currentMinute, true).show()
        }
    }

    private fun formatTime(hour: Int, minute: Int): String {
        return "%02d:%02d".format(hour, minute)
    }

    private fun updateReminderStatus(enabled: Boolean, hour: Int, minute: Int) {
        binding.tvReminderStatus.visibility = View.VISIBLE
        if (enabled) {
            binding.tvReminderStatus.setTextColor(Color.parseColor("#5DB86A"))
            binding.tvReminderStatus.text = "Reminder set for ${formatTime(hour, minute)} daily"
        } else {
            binding.tvReminderStatus.setTextColor(Color.parseColor("#888888"))
            binding.tvReminderStatus.text = "Reminders disabled"
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
