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

    /**
     * Cellist quotes — each pair is (author, quote body). A random entry
     * is shown on the splash screen each time the app opens.
     */
    private val quotes = listOf(
        "Jacqueline du Pré" to
            "Playing lifts you out of yourself into a delirious place.",
        "Mstislav Rostropovich" to
            "My mother carried me for 10 months. I asked her 'Mother, you had an extra month, why you didn't make me a beautiful face?' and mother told me, 'My son, I was busy making your beautiful hands and heart.'",
        "Anita Lasker-Wallfisch" to
            "As long as we can breathe, we can hope.",
        "Yo-Yo Ma" to
            "I think one of the great things about being a musician is that you never stop learning.",
        "Natalia Gutman" to
            "If you had lived for at least a month in Russia during the Soviet Empire's reign you would understand our character!",
        "Julian Lloyd Webber" to
            "Cello players, like other great athletes, must keep their fingers exercised.",
        "Mischa Maisky" to
            "I mainly change shirts because I perspire a lot as I play.",
        "Pablo Casals" to
            "Don't play the notes. Play the meaning of the notes.",
        "Aldo Parisot" to
            "Nuances are not in the notes. They come from the player.",
        "Gregor Piatigorsky" to
            "I'll stop teaching when I stop learning.",
        "Astrid Schween" to
            "I try to not wait until I am too comfortable.",
        "Lynn Harrell" to
            "There's a need to feel a great deal of confidence, but you have to be very sensitive to the inward, frightened, timid side of human nature.",
        "János Starker" to
            "Don't hold a note just because you love it. Keep in mind what the composer intends.",
        "Steven Isserlis" to
            "If you worry about hitting notes, odds are you will miss a lot of them.",
        "Heinrich Schiff" to
            "Art can't follow the people's taste to the pleasure of the box office.",
        "Stjepan Hauser" to
            "Great music is great music. It doesn't matter what genre it belongs.",
        "Dale Henderson" to
            "We need to invite people IN to the world of classical music – not keep them out!",
        "Alisa Weilerstein" to
            "Keep returning to old pieces and find new things to discover about them—there is always something that has been overlooked!",
        "Zuill Bailey" to
            "The cello is the most perfect instrument aside from the human voice.",
        "Bernard Greenhouse" to
            "Trio life has the wonderful advantage of not being alone, the pleasure of having success with two other people and the solace when you don't have success."
    )

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

        // Show a random cellist quote
        val (author, body) = quotes.random()
        binding.tvQuoteBody.text = "\u201C$body\u201D"
        binding.tvQuoteAuthor.text = "— $author"

        startMusic()

        binding.touchOverlay.setOnClickListener {
            stopMusic()
            requireActivity().window.clearFlags(WindowManager.LayoutParams.FLAG_FULLSCREEN)
            findNavController().navigate(R.id.action_splash_to_library)
        }
    }

    private fun startMusic() {
        try {
            val afd = requireContext().assets.openFd("Sovereign_Timber.mp3")
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
