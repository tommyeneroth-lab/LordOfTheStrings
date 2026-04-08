# LordOfTheStrings 🎻

A full-featured Android app for cello musicians — scan and play music scores, tune your cello, and keep time with a precision metronome.
<img width="2816" height="1536" alt="Gemini_Generated_Image_5ep8re5ep8re5ep8" src="https://github.com/user-attachments/assets/b2d9afd6-dcf7-4d1a-8c31-e981f77debcd" />

## Features

### Score Library
- List view with color-coded source-type badges (XML, PDF, IMG, MIDI, audio)
- Search by title or composer
- Favorites, metadata (key, time signature, measure count)
- Edit title and composer from the library
- Import from MusicXML, PDF, JPEG/photo, MIDI, or MP3/audio
- JPEG and PDF import can use a remote OMR server when configured

### Score Viewer & Playback
- Renders full music notation on a pannable, zoomable canvas
- **Duration-proportional note spacing** — dense 16th-note passages get wider measures
- **Playback cursor** highlights the current note in real time
- **Auto-scroll** follows the played note (positioned at 20% from left, showing upcoming notes)
- Adjustable playback speed (25% – 200%) and volume
- Transpose control (up/down by semitones)
- Jump to any measure with the progress bar
- **Score editing**: select any note or rest to change pitch, duration, clef, or delete
  - Clef changes preserve visual staff positions (pitch is recalculated)
- **Fingering advisor** with position markers (Roman numerals for non-1st positions)
- **Export** to MIDI, MusicXML, or PDF (with correct file extensions)
- Handles all standard cello notation:
  - Bass, tenor, and treble clef (with automatic switching)
  - All note values (whole through 128th), dots, tuplets
  - Dynamics (pp – ffff, sfz, fp)
  - Hairpins (crescendo / decrescendo)
  - Slurs and ties
  - Articulations: staccato, tenuto, accent, marcato, fermata
  - Bowing marks: up bow, down bow
  - Cello techniques: pizzicato, arco, col legno, sul ponticello, sul tasto, harmonics
  - Fingering numbers (0 – 4) with position indicators
  - Ornaments: trills, turns, mordents
  - Repeats, volta brackets (1st/2nd endings), da capo, dal segno
  - Rehearsal marks

### PDF Export
- Professional layout with title, composer (italic), and instrument label
- Duration-weighted measure spacing — no crowded notes
- Key signatures, time signatures, tempo marks, and double barline at end
- Fingering numbers included when enabled in the viewer
- Subtle "Lord of the Strings" watermark on every page
- Page numbers and footer branding

### Cello Tuner
- Microphone input with **Harmonic Product Spectrum (HPS)** pitch detection
  - Correctly identifies cello C2 (65 Hz) despite its weak fundamental
- Animated semicircular needle gauge
  - Green zone: ±5 cents (in tune)
  - Yellow zone: ±5 to ±20 cents
  - Red zone: beyond ±20 cents
- Tap **C2 / G2 / D3 / A3** to play the open string reference tone through the speaker
- Displays detected frequency, note name, and cents deviation

### Metronome
- Sample-accurate click with **zero drift** (AudioTrack sample-count timing)
- BPM range: 20 – 300
- Tap Tempo button
- Configurable time signature (numerator and denominator)
- Animated pendulum with beat-number indicator
- Distinct downbeat and upbeat click tones

### Audio Recording & Transcription
- Record directly from the microphone
- Automatic pitch detection and transcription to notation
- Mono recording with stereo downmix support
- HPS pitch detection with corrected DIF FFT and median filtering

### Score Import
| Source | Method |
|--------|--------|
| **MusicXML** | Direct parse — full notation fidelity |
| **PDF** | Renders each page and runs OMR (local or server) |
| **Camera / JPEG** | Captures via CameraX and runs OMR (local or server) |
| **MIDI** | Parsed into notation with correct durations |
| **MP3 / Audio** | Pitch detection → note transcription |

OMR pipeline: adaptive binarization → staff line detection → connected-component symbol classification → MusicXML assembly. When an OMR server URL is configured in settings, PDF and JPEG imports are sent to the server first with automatic fallback to on-device processing.

> **Want better OMR accuracy?** Set up the companion server — see [`server/README.md`](server/README.md) for instructions.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9 |
| UI | Fragments + ConstraintLayout + Material 3 |
| Navigation | Jetpack Navigation Component |
| Database | Room |
| Camera | CameraX |
| Audio I/O | AudioRecord, AudioTrack, MediaCodec, Android MIDI API |
| FFT | Custom radix-2 DIF FFT with HPS pitch detection |
| PDF | Android PdfRenderer + pdfbox-android |
| Image loading | Coil |
| Async | Coroutines + Flow |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Project Structure

```
├── server/                 # OMR server (Python/Flask + oemer)
│   ├── omr_server.py       # Server entry point
│   ├── requirements.txt    # Python dependencies
│   ├── Dockerfile          # Docker build
│   └── README.md           # Setup instructions
│
└── app/src/main/java/com/cellomusic/app/
    ├── audio/
    │   ├── tuner/          # HPS pitch detection, FFT analysis
    │   ├── playback/       # MIDI encoder, score player, SMF writer
    │   └── metronome/      # Sample-accurate click engine
    ├── data/
    │   ├── db/             # Room database, DAOs, entities
    │   └── repository/     # Score import, storage, CRUD
    ├── domain/model/       # Score, Measure, Note, Pitch, Articulation, ...
    ├── musicxml/           # Streaming MusicXML parser (XmlPullParser)
    ├── omr/                # On-device OMR pipeline + server client
    ├── export/             # MIDI, MusicXML, and PDF export with watermark
    ├── ui/
    │   ├── library/        # Score list, search, import dialog
    │   ├── viewer/         # Score canvas renderer + playback controls + editing
    │   ├── tuner/          # Tuner gauge view, reference tones
    │   ├── metronome/      # Pendulum view, tap tempo
    │   └── import_/        # CameraX capture screen
    └── util/               # Note frequency utilities
```

## Getting Started

### Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android device or emulator — API 26+

### Build
1. Clone the repo:
   ```bash
   git clone https://github.com/tommyeneroth-lab/LordOfTheStrings.git
   ```
2. Open in **Android Studio** via *File → Open*
3. Wait for Gradle sync to complete
4. Run on a physical device for best audio performance

### Importing Scores
For the most reliable results, export from your notation software as **MusicXML**:
- MuseScore: *File → Export → MusicXML*
- Sibelius: *File → Export → MusicXML*
- Finale: *File → MusicXML → Export*
- Dorico: *File → Export → MusicXML*

## License

This project is released for personal and educational use.
