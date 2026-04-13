package com.cellomusic.app.ui.splash

import android.media.MediaPlayer
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.fragment.app.Fragment
import androidx.navigation.fragment.findNavController
import com.cellomusic.app.R
import com.cellomusic.app.databinding.FragmentSplashBinding

class SplashFragment : Fragment() {

    private var _binding: FragmentSplashBinding? = null
    private val binding get() = _binding!!
    private var mediaPlayer: MediaPlayer? = null

    override fun onCreateView(
        inflater: LayoutInflater, container: ViewGroup?,
        savedInstanceState: Bundle?
    ): View {
        _binding = FragmentSplashBinding.inflate(inflater, container, false)
        return binding.root
    }

    override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
        super.onViewCreated(view, savedInstanceState)

        requireActivity().window.addFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)

        startMusic()

        binding.touchOverlay.setOnClickListener {
            stopMusic()
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            findNavController().navigate(R.id.action_splash_to_library)
        }
    }

    private fun startMusic() {
        try {
            val afd = requireContext().assets.openFd("The_Weight_of_the_Nave.mp3")
            mediaPlayer = MediaPlayer().apply {
                setDataSource(afd.fileDescriptor, afd.startOffset, afd.length)
                afd.close()
                isLooping = true
                prepare()
                start()
            }
        } catch (_: Exception) {
            // Audio playback not supported on this device — silent splash is fine
        }
    }

    private fun stopMusic() {
        try { mediaPlayer?.stop() } catch (_: Exception) {}
        try { mediaPlayer?.release() } catch (_: Exception) {}
        mediaPlayer = null
    }

    override fun onPause() {
        super.onPause()
        stopMusic()
    }

    override fun onDestroyView() {
        super.onDestroyView()
        stopMusic()
        _binding = null
    }
}
