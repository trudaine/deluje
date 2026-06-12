# Deluge Hardware Fidelity Verification Plan

This plan outlines how to test and verify the audio fidelity of the ChucK-Java `deluge/` engine against the real physical Synthstrom Deluge hardware.

---

## 0. Test-song XML format (IMPORTANT for anyone regenerating them)

The three test songs (`TestSynthFidelity.xml`, `TestUnisonFidelity.xml`, `TestKitFidelity.xml`)
are written in the **exact format the community firmware c1.2.0 writes** (modeled verbatim on a
real hardware-saved song — `SONGS/Dx7A.xml`). The firmware's loader binds each
`<instrumentClip>` to its instrument **by name** (`instrumentPresetName` ↔ the instrument's
`presetName`, plus matching `presetFolder`); a song without that linkage fails in
`InstrumentClip::claimOutput` with `FILE_CORRUPTED` and the Deluge refuses to load it (verified
against the `DelugeFirmware` NIGHTLY @ `9a74e162` / nightly-4, model/clip/instrument_clip.cpp:3867
— so this applies to both c1.2.0 stable and current nightlies; there is no fallback path). Other essentials:

* Song-level settings are **attributes** on `<song>`; tempo is `timePerTimerTick="229"` +
  `timerTickFraction="-1342177280"` (= 229.6875 samples/tick × 96 PPQN → 120 BPM), not a
  `tempo` attribute.
* Synth params live in the **clip's** `<soundParams>` (kit: `<kitParams>` + per-noteRow
  `<soundParams>`), not in the instrument's `defaultParams` (that's the preset-file format).
* Notes are `noteDataWithLift` blobs: per note 11 bytes = pos(8) + length(8) + velocity(2) +
  lift(2) + probability(2) hex chars; `14` = 100% probability.
* Kit drums are full `<sound>` elements inside `<soundSources>` with
  `<osc1 type="sample" loopMode="1" fileName="SAMPLES/..."><zone startSamplePos endSamplePos/>`.
* `osc1 retrigPhase="0"` in the synth tests pins the start phase so hardware recordings are
  phase-deterministic and comparable.

`FidelitySongSmokeTest` guards that our own parser + engine also load and sound these files.

---

## 1. Setup of physical Deluge SD Card

To ensure the hardware uses the exact same sample and preset configurations:
1. Copy the following resource directories from ChucK-Java to the root of your Deluge SD card:
   * Copy `deluge/src/main/resources/SAMPLES/` to the SD card's `/SAMPLES/` folder.
   * Copy `deluge/src/main/resources/SONGS/` to the SD card's `/SONGS/` folder.
   * Copy `deluge/src/main/resources/SYNTHS/` to the SD card's `/SYNTHS/` folder.
   * Copy `deluge/src/main/resources/KITS/` to the SD card's `/KITS/` folder.

> [!NOTE]
> This matches the directory structure expected by the Deluge firmware, ensuring that `SONG006668.XML` correctly resolves the path `SAMPLES/DRUMS/Kick/808 Kick.wav` dynamically.

---

## 2. Step-by-Step Recording Guide

You can choose either of the following two recording methods depending on your setup.

---

### Option A: Recording via Audio Interface (DAW)

#### Phase 1: Hardware Connections
1. Connect the **Left and Right Output** jacks (1/4" TS/TRS) of the Deluge to two line-level inputs on your audio interface.
2. In your DAW (e.g., Ableton, Logic, Reaper), create a **Stereo Audio Track** mapped to those two inputs.
3. Ensure no audio effects, EQ, limiters, or compression are active on your DAW track. It must be a completely dry stereo recording.

#### Phase 2: Gain Staging
1. Turn the physical **volume knob** of the Deluge to a fixed spot (e.g. 12 o'clock / 50% or maximum).
2. Set the input gain on your audio interface so that the signal peaks around **-6dB to -12dB FS**. Avoid any clipping (red lights) on your interface.

#### Phase 3: Loading and Playing Songs
For each of the test files (`TestSynthFidelity.xml`, `TestKitFidelity.xml`, `SONG006668.XML`, `TestUnisonFidelity.xml`):
1. Safely eject the SD card from your computer and insert it into the Deluge.
2. Power on the Deluge.
3. Press the **SONG** button to enter Song mode (if not already active).
4. Turn the **SELECT knob** to browse songs. The screen will display the filenames. Locate the song you want to test (e.g., `TSYNTHFI` for `TestSynthFidelity`, `TKITFIDE` for `TestKitFidelity`, `SONG006668` or `TSONG`, `TUNISONF` for `TestUnisonFidelity`).
5. Press the **SELECT knob** to load the song.
6. Arm the track in your DAW and start recording.
7. Press the physical **PLAY** button on the Deluge.
8. Wait for the required recording duration:
   - For `TestSynthFidelity`: 3.0 seconds.
   - For `TestKitFidelity`: 3.0 seconds.
   - For `TestUnisonFidelity`: 3.0 seconds.
   - For `SONG006668.XML`: 10.0 seconds.
9. Press **PLAY** (or **STOP**) on the Deluge to stop playback.
10. Stop recording in your DAW.
11. Export the recorded region as a **44.1 kHz, 16-bit or 24-bit Stereo WAV file** (e.g. `recorded_synth.wav`, `recorded_kit.wav`, etc.).

---

### Option B: Resampling directly on the Deluge (No Audio Interface Required)

This method records the master stereo output digitally onto the SD card, ensuring a bit-accurate capture without external noise or latency.

> [!TIP]
> **Shortcut**: Instead of the manual setup steps below, you can simply press **SHIFT + RECORD** on the physical Deluge. This instantly creates a new audio track, sets its input to `MIX`, arms it, and starts recording!

1. Power on the Deluge and load the test song (e.g., `TSYNTHFI`).
2. Add a new **Audio Track**:
   - Hold down the **SELECT knob** and press one of the launch pads in an empty row to create an audio track.
3. Set the track's input source to the master output mix:
   - Hold down the track's row launch button and scroll the **SELECT knob** until the screen reads **`MIX`**.
4. Arm the track:
   - Press the track's launch pad so it blinks red (recording armed).
5. Start recording:
   - Press **RECORD**, then press **PLAY**. The song will play and record the master output directly to the SD card.
6. Wait for the required recording duration (e.g., 3.0 seconds or 10.0 seconds), then press **STOP**.
7. Retrieve the file:
   - Power off the Deluge, eject the SD card, and insert it into your computer.
   - The file is saved in the `/SAMPLES/` directory (typically in `/SAMPLES/RECORD/` or sequentially named like `REC0001.WAV`). Rename the file to match the scenario (e.g., `recorded_synth.wav`).

---

## 3. Test Targets & Recording Instructions

Run these four test scenarios on your physical Deluge and record their stereo outputs.

### Test Scenario A: Automated FM Synth
* **Song File:** `/SONGS/TestSynthFidelity.xml` (Loads the Basic FM patch and plays a C4 note for 1.0 seconds at 120 BPM).
* **Playback:** Start playback from the beginning.
* **Recording Length:** ~3.0 seconds (to capture the release tail).

### Test Scenario B: Automated TR-808 Kit
* **Song File:** `/SONGS/TestKitFidelity.xml` (Loads the TR-808 Kit and triggers a Kick at step 0, Snare at step 4).
* **Playback:** Start playback from the beginning.
* **Recording Length:** ~3.0 seconds.

### Test Scenario C: Full Arrangement Song
* **Song File:** `/SONGS/SONG006668.XML` (Arrangement with multiple concurrent synth and kit sequences).
* **Playback:** Start playback from the beginning.
* **Recording Length:** 10.0 seconds.

### Test Scenario D: Automated Unison Synth
* **Song File:** `/SONGS/TestUnisonFidelity.xml` (Loads the Rich Saw Bass preset, featuring 4 unison voices detuned at 10, and plays a C2 note for 1.0 second at 120 BPM).
* **Playback:** Start playback from the beginning.
* **Recording Length:** ~3.0 seconds.

### Recording Standards:
* Record the Deluge master stereo line-out dry (no external hardware effects or gain boosts).
* Output format: **44.1 kHz, 16-bit or 24-bit, Stereo WAV**.

---

## 4. Rendering the Reference wave from ChucK-Java

To render the identical reference files offline, run the compiled test runner:

```bash
# Render Scenario A: Synth (3.0 seconds)
mvn exec:java -Dexec.classpathScope="test" \
  -Dexec.mainClass="org.chuck.deluge.FidelityTestRunner" \
  -Dexec.args="deluge/src/main/resources/SONGS/TestSynthFidelity.xml rendered_synth.wav 3.0"

# Render Scenario B: Kit (3.0 seconds)
mvn exec:java -Dexec.classpathScope="test" \
  -Dexec.mainClass="org.chuck.deluge.FidelityTestRunner" \
  -Dexec.args="deluge/src/main/resources/SONGS/TestKitFidelity.xml rendered_kit.wav 3.0"

# Render Scenario C: Song (10.0 seconds)
mvn exec:java -Dexec.classpathScope="test" \
  -Dexec.mainClass="org.chuck.deluge.FidelityTestRunner" \
  -Dexec.args="deluge/src/main/resources/SONGS/SONG006668.XML rendered_song.wav 10.0"

# Render Scenario D: Unison (3.0 seconds)
mvn exec:java -Dexec.classpathScope="test" \
  -Dexec.mainClass="org.chuck.deluge.FidelityTestRunner" \
  -Dexec.args="deluge/src/main/resources/SONGS/TestUnisonFidelity.xml rendered_unison.wav 3.0"
```

---

## 5. Comparing the Hardware Recording against JVM Reference

Once you have recorded the hardware WAV file (e.g., `recorded_song.wav`), run the alignment and comparison tool:

```bash
mvn exec:java -Dexec.classpathScope="test" \
  -Dexec.mainClass="org.chuck.deluge.FidelityComparisonTool" \
  -Dexec.args="rendered_song.wav recorded_song.wav"
```

This tool automatically:
1. Performs normalized cross-correlation across a 2-second range to locate the exact time alignment lag.
2. Computes the Root Mean Square Error (RMSE), Mean Absolute Error (MAE), and peak cross-correlation percentage.
3. Generates a detailed audio parity report.
