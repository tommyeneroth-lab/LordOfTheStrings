# Cello Music App - Setup Guide

## Requirements
- Android Studio Hedgehog (2023.1.1) or newer
- JDK 17
- Android device or emulator running Android 8.0+ (API 26+)
- Recommended: physical device for microphone/audio testing

## Opening the Project
1. Open Android Studio
2. Choose **File → Open**
3. Navigate to `CelloMusicApp/` and click OK
4. Wait for Gradle sync to complete (~2-5 minutes first time)

## Dependencies Auto-Downloaded by Gradle
- `JTransforms` — FFT for tuner pitch detection
- `Room` — Score library database
- `Navigation Component` — Fragment navigation
- `CameraX` — Camera capture for score scanning
- `Coil` — Score thumbnail image loading
- `pdfbox-android` — PDF rendering/import
- `Material 3` — UI components

## Running the App
1. Connect an Android device with USB debugging enabled, OR create an emulator
2. Click the **Run** button (▶) in Android Studio

## Features Overview

### Score Library
- Tap **+** to import a score
- Supports: MusicXML (.xml), PDF, and JPEG/camera photos
- Scores are saved locally in the app's internal storage
- Search by title or composer name
- Long-press a score card to favorite or delete

### Score Viewer & Playback
- Tap a score to open it
- Pinch to zoom, drag to pan
- Press **▶ Play** to start playback — a blue cursor tracks the current note
- **Speed slider** adjusts playback from 25% to 200% of written tempo
- **Progress bar** lets you jump to any measure

### Cello Tuner
- Shows a semicircular gauge with needle indicator
- Green zone = ±5 cents (in tune)
- Tap **C2, G2, D3, A3** to play reference tones through speaker
- Mic input uses HPS (Harmonic Product Spectrum) algorithm — works correctly
  for cello's weak C2 fundamental

### Metronome
- Adjustable BPM 20–300 via slider
- **Tap Tempo** button: tap repeatedly to set BPM from your playing
- Time signature selector (numerator and denominator)
- Sample-accurate click with no timing drift
- Pendulum animation shows the beat visually

## Score Import Notes

### MusicXML Import
Most reliable option. Export from:
- **MuseScore** (File → Export → MusicXML)
- **Sibelius** (File → Export → MusicXML)
- **Finale** (File → MusicXML → Export)
- **Dorico** (File → Export → MusicXML)

### PDF Import
The app renders PDF pages and runs OMR on them.
Best results with:
- Scanned scores at 300 DPI or higher
- Clean, printed notation (not handwritten)
- Standard notation (not guitar tablature)

### Camera Capture
- Hold the device steady over the score page
- Ensure good lighting, avoid shadows
- The guide rectangle shows the capture area
- OMR accuracy: ~80% for clean printed scores

## MusicXML Notation Support
The score viewer renders:
- All note values (whole through 128th)
- Both bass clef and tenor clef (cello switch)
- All standard key signatures
- All time signatures including compound meters
- Dynamics (pp through ffff, sfz, fp)
- Hairpins (crescendo, decrescendo)
- Slurs and ties (Bezier curves)
- Staccato, tenuto, accent, marcato
- Fermatas, breath marks
- Up bow / down bow markings
- Harmonics (circle above note)
- Pizzicato / arco text directions
- Col legno, sul ponticello, sul tasto
- Trills and ornaments
- Fingering numbers (0–4)
- Volta brackets (1st/2nd endings)
- Repeat barlines
- Rehearsal marks

## Troubleshooting

**Gradle sync fails**: Check internet connection; dependencies are downloaded from Maven Central.

**Tuner not detecting pitch**: Grant microphone permission when prompted. Ensure no other app is using the mic.

**Score doesn't play sound**: On emulator, MIDI output may not be available — use a physical device for playback testing.

**OMR result is inaccurate**: For complex scores, use MusicXML import instead. OMR works best on clean, high-contrast printed scores.

**"No staff lines detected"**: Ensure the image contains clearly visible horizontal staff lines. Poor lighting or low resolution reduces detection accuracy.
