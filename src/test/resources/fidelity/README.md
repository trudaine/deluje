# Deluge Physical-to-Digital Audio Parity Reference Assets

This folder contains Git-tracked physical Deluge reference audio waves and their corresponding XML track preset configuration templates used to verify DSP engine parity down to near-perfect wave correlation indices (>= 90%).

---

## 1. Dry Sawtooth Reference (Patch A)
* **WAV Capture:** `reference_rec07.wav`
  * *Method:* Master Resampling (Method A - Stereo digital bounce from Deluge SD Card, averaged to Mono).
  * *Pitch:* C5 ($523.25\text{ Hz}$ fundamental, MIDI note 72).
* **XML Preset:** `098_DRY_SAW_C5.XML`
  * *Track Type:* Subtractive Synth
  * *Osc 1:* Dry SAW, 100% Volume
  * *Osc 2:* NONE
  * *LPF/HPF Filters:* Off
  * *FX sends:* All 0 (fully dry)

---

## 2. Filtered Sawtooth Sweep Reference (Patch B)
* **WAV Capture:** `reference_filtered_saw_c5.wav`
  * *Method:* Analog Line-Out connection (Method B - recorded direct to audio interface at 44.1kHz).
  * *Pitch:* C5 ($523.25\text{ Hz}$ fundamental, MIDI note 72).
* **XML Preset:** `099_FILTERED_SAW_C5.XML`
  * *Track Type:* Subtractive Synth with custom cutoff envelope mod.
  * *LPF Cutoff:* $10\text{ kHz}$
  * *LPF Resonance:* $0.0$
  * *Filter Mode:* Transistor / Ladder 24dB

---

## 3. Legacy Analog Line-Out Dries
* **WAV Captures:**
  * `reference_dry_saw_c4.wav` (actual pitch is C3 / $130.86\text{ Hz}$ fundamental due to manual unit knob/transposition configurations).
  * `reference_saw_c4.wav` (C6 analog pulse-modulated square waves).

---

## 4. New High-Fidelity Test Targets (Pending WAV Captures)
We have generated 5 new generic XML preset templates to cover all major synthesis components (Subtractive detunes, filter sweeps, PWM interpolation, 2-Operator FM, and 6-Operator Vintage DX7):

*   **`100_DETUNED_SAW_C5.XML` (Patch C - Subtractive Dual Detune):**
    *   *Osc 1:* SAW, 100% Volume
    *   *Osc 2:* SAW, 100% Volume, transposed detune = +15 cents
    *   *LPF/HPF Filters:* Off
*   **`101_FILTER_MOD_SAW_C5.XML` (Patch D - Cutoff Envelope Sweep):**
    *   *Osc 1:* SAW, 100% Volume, LPF Mode = Transistor 24dB
    *   *LPF Cutoff:* 2000Hz base
    *   *LPF Resonance:* High (ringing resonance 0x50000000)
    *   *Envelope 2 (Mod Env):* Instant attack, short snappy decay mod target to LPF frequency
*   **`102_PWM_SQUARE_C5.XML` (Patch E - Pulse Width Modulation):**
    *   *Osc 1:* SQUARE wave, modulated by a slow LFO 1 (triangle wave) target to osc1PulseWidth
*   **`103_FM_SIMPLE_C5.XML` (Patch F - 2-Operator FM):**
    *   *Carrier (Osc 1):* SINE wave
    *   *Modulator (Osc 2):* SINE wave, ratio = 2.0 (octave above), mod amount = 0.5
*   **`104_DX7_VINTAGE_C5.XML` (Patch G - DX7 Vintage FM E.Piano):**
    *   *Osc 1:* DX7 type, running vintage 14-bit integer lookup model (engineMode = 1) loaded with Yamaha DX7 classic sysex block data

### Recording Instructions:
For each of these XML files loaded onto your physical Deluge:
1. Record a single **C5 note (MIDI Note 72, at pitch 72)** triggered at step 0, and held for exactly **4 seconds** (legato gate, allowing full decay/sustain profiles).
2. Perform a digital master resample bounce (Method A) to yield a stereo 44.1kHz WAV file, name it accordingly (e.g. `reference_rec10.wav`), and save it to this directory to bind to automated tests.

---

## 5. Running the Waveform Parity Comparison Tool
You can execute the automated phase-aligning waveform comparison tool directly against these local Git-tracked resources from the command line:

```bash
# Force compile and run the default C5 Dry Sawtooth (Patch A) comparison:
mvn test-compile && mvn -pl deluge exec:java -Dexec.classpathScope="test" -Dexec.mainClass="org.chuck.deluge.reproduce.CompareAudioParity"
```

The tool will auto-align zero-crossing phases, perform peak-normalization, and print:
1. Aligned transient delay onset offsets.
2. 100ms drift-free window shape cross-correlation indexes (Target >= 90%).
3. Active amplitude levels, RMS ratios, and fundamental pitch frequency correlation checks.
