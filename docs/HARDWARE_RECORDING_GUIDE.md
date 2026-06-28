# Deluge Hardware Fidelity Recording Guide

This guide explains how to record a hardware reference WAV from a physical Deluge synthesizer to be used in the high-fidelity regression tests (specifically `PhysicalHardwareFidelityTest`).

Because the tests compare the software engine's output against the hardware recording at a sample-accurate level, it is **crucial** that the recording is done with the exact same preset, MIDI note, velocity, and alignment.

---

## Step 1: Prepare the Preset
1. Locate the corrected XML preset in the repository: `src/test/resources/fidelity/104_DX7_VINTAGE_C5.XML`.
2. Copy this file to your Deluge's SD card under the `SYNTHS/` directory (e.g., rename it to `104.XML` or any unused number).
3. Insert the SD card back into the Deluge.

## Step 2: Hardware Connections
1. Connect the Deluge's **Line Out (L/R)** (using 1/4" TS jacks) to your recording computer's audio interface inputs or Line In.
2. Connect the Deluge to your computer via **USB** to send MIDI notes.
3. Turn on the Deluge and load the preset you copied (e.g., Synth `104`).

## Step 3: Recording Settings (DAW / Audio Editor)
In your DAW (e.g., Audacity, Reaper, Ableton, etc.), configure the recording track:
* **Format**: WAV (PCM)
* **Sample Rate**: **44,100 Hz** (Crucial! The engine runs at 44.1kHz).
* **Bit Depth**: **16-bit** signed PCM.
* **Channels**: **Stereo** (or Mono, but Stereo is preferred as the Deluge output is stereo).
* **Gain/Level**: Adjust the Deluge volume and your interface gain so the peak level of the playing note reaches between **-3 dB and -6 dB**. Avoid any analog or digital clipping, but ensure a healthy signal-to-noise ratio.

## Step 4: Play and Record the Note
To trigger the note, you should send a MIDI note from your computer to the Deluge to guarantee the exact velocity:
* **MIDI Channel**: The channel your synth track is listening to (usually Channel 1).
* **MIDI Note**: **72** (C5)
* **MIDI Velocity**: **100** (Crucial! The engine scales envelopes and levels based on velocity; it must be exactly 100).
* **Note Duration**: Hold the note for at least **3.0 seconds**, then release.

**Recording Process**:
1. Start recording in your DAW.
2. Wait about 1–2 seconds (leave some silence at the start).
3. Play the MIDI note (C5, velocity 100, hold for 3s, then release).
4. Wait 1–2 seconds after the note fully decays/releases.
5. Stop recording.
6. Export the recording as a **16-bit Stereo WAV at 44.1kHz**.

## Step 5: Automatically Align the Recording
The test suite expects the note trigger to happen at exactly **sample 57,856** (block 452). Manually aligning this in a DAW is extremely difficult. 

We have provided a Python script in the repository to align your recording automatically:
1. Copy your raw recording WAV to your computer.
2. Run the alignment script from the root of the repository:
   ```bash
   python scripts/align_recording.py <path_to_your_raw_recording.wav> src/test/resources/fidelity/reference_dx7_vintage_c5.wav
   ```
3. The script will:
   * Detect the exact onset of the note.
   * Pad or trim the beginning of the file so the onset land exactly on sample 57,856.
   * Overwrite the reference file in the test resources with the perfectly aligned version.

## Step 6: Re-enable and Run the Test
1. Open `src/test/java/org/deluge/PhysicalHardwareFidelityTest.java`.
2. Find the `testDx7VintageParity` method (around line 1160).
3. Remove the `@Disabled` annotation:
   ```java
   // Remove this line:
   @Disabled("Disabled due to corrupted hardware reference file...")
   @Test
   public void testDx7VintageParity() throws Exception {
   ```
4. Run the test to verify parity:
   ```bash
   mvn test -Pslow-tests -Dtest=PhysicalHardwareFidelityTest#testDx7VintageParity
   ```
5. If the test passes (spectral correlation $\ge 0.85$), you are done! Commit and push the new `reference_dx7_vintage_c5.wav` and the re-enabled test.
