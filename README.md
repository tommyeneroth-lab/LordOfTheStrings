# LordOfTheStrings 🎻

A full-featured Android app for cello musicians — scan and play music scores, tune your cello, and keep time with a precision metronome.

## Features

### Score Library
- Store and manage a large collection of cello scores
- Search by title or composer
- Thumbnail previews, favorites, and metadata (key, time signature, measure count)
- Import from MusicXML, PDF, or camera photo

### Score Viewer & Playback
- Renders full music notation on a zoomable canvas
- **Playback cursor** highlights the current note in real time
- Adjustable playback speed (25% – 200% of written tempo)
- Jump to any measure with the progress bar
- Handles all standard cello notation:
  - Bass clef and tenor clef (with automatic switching)
  - All note values (whole through 128th), dots, tuplets
  - Dynamics (pp – ffff, sfz, fp)
  - Hairpins (crescendo / decrescendo)
  - Slurs and ties
  - Articulations: staccato, tenuto, accent, marcato, fermata
  - Bowing marks: up bow, down bow
  - Cello techniques: pizzicato, arco, col legno, sul ponticello, sul tasto, harmonics
  - Fingering numbers (0 – 4)
  - Ornaments: trills, turns, mordents
  - Repeats, volta brackets (1st/2nd endings), da capo, dal segno
  - Rehearsal marks

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

### Score Import
| Source | Method |
|--------|--------|
| **MusicXML** | Direct parse — full notation fidelity |
| **PDF** | Renders each page and runs OMR |
| **Camera / JPEG** | Captures via CameraX and runs OMR |

OMR pipeline: adaptive binarization → staff line detection → connected-component symbol classification → MusicXML assembly. Best accuracy on clean printed scores at 300 DPI+.

## Tech Stack

| Layer | Technology |
|-------|-----------|
| Language | Kotlin 1.9 |
| UI | Fragments + ConstraintLayout + Material 3 |
| Navigation | Jetpack Navigation Component |
| Database | Room |
| Camera | CameraX |
| Audio I/O | AudioRecord, AudioTrack, Android MIDI API |
| FFT | JTransforms |
| PDF | Android PdfRenderer + pdfbox-android |
| Image loading | Coil |
| Async | Coroutines + Flow |
| Min SDK | 26 (Android 8.0) |
| Target SDK | 34 (Android 14) |

## Project Structure

```
app/src/main/java/com/cellomusic/app/
├── audio/
│   ├── tuner/          # HPS pitch detection, FFT analysis
│   ├── playback/       # MIDI encoder, score player, SMF writer
│   └── metronome/      # Sample-accurate click engine
├── data/
│   ├── db/             # Room database, DAOs, entities
│   └── repository/     # Score import, storage, CRUD
├── domain/model/       # Score, Measure, Note, Pitch, Articulation, ...
├── musicxml/           # Streaming MusicXML parser (XmlPullParser)
├── omr/                # Optical Music Recognition pipeline
├── ui/
│   ├── library/        # Score grid, search, import dialog
│   ├── viewer/         # Score canvas renderer + playback controls
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
