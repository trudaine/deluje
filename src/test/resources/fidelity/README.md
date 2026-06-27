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
mvn test-compile && mvn exec:java -Dexec.classpathScope="test" -Dexec.mainClass="org.deluge.reproduce.CompareAudioParity"
```

The tool will auto-align zero-crossing phases, perform peak-normalization, and print:
1. Aligned transient delay onset offsets.
2. 100ms drift-free window shape cross-correlation indexes (Target >= 90%).
3. Active amplitude levels, RMS ratios, and fundamental pitch frequency correlation checks.

---

## 6. Extreme Boundary & Summing Test Targets (Pending WAV Captures)
We have generated 4 new extreme stress-testing templates to validate dynamic engine boundary checks, multi-voice summing under full spread, unity delay lines, and fast arpeggiator gate dynamics:

*   **`123_HIGH_LFO_RATE_C5.XML` (High LFO Rate Nyquist Boundary):**
    *   *Osc 1:* Triangle wave
    *   *LFO 1:* Sine wave running at absolute maximum speed (`rate = 0x7FFFFFFF` $\approx 100\text{ Hz}$) targeted to pitch modulation (`amount = 0x30000000`).
    *   *Recording instructions:* Record a **C5 note (MIDI Note 72)** for 4 seconds. Save the resampled mono/stereo wave as `reference_lfo_high_rate_c5.wav`.
*   **`124_SATURATED_DELAY_FEEDBACK_C5.XML` (Saturated Unity Delay Feedback):**
    *   *Osc 1:* Sawtooth wave
    *   *Delay:* Delay rate at `0x30000000`, delay feedback at maximum limit (`0x7FFFFFFF` representing unity loop gain). Summing paths must maintain robust clipping protection under infinite resonance.
    *   *Recording instructions:* Record a brief, single staccato **C5 note (MIDI Note 72)** (hold for 100ms then release) to capture the infinite tail response. Save the resampled wave as `reference_saturated_delay_feedback_c5.wav`.
*   **`125_EIGHT_VOICE_UNISON_SAW_C5.XML` (8-Voice Unison Summing):**
    *   *Osc 1:* Sawtooth wave
    *   *Unison:* Voice count set to maximum 8, detuning to `25.0` cents, stereo spread to maximum `1.0`. Enforces multi-voice virtual engine summing stability.
    *   *Recording instructions:* Record a **C4 note (MIDI Note 48, triggered at pitch 48)** for 4 seconds. Save the resampled wave as `reference_eight_voice_unison_saw_c5.wav`.
*   **`126_ARPEGGIATOR_GATE_SPREAD_C5.XML` (Dynamic Arpeggiator Gate Spread):**
    *   *Osc 1:* Sawtooth wave
    *   *Arpeggiator:* Active mode down, sequence length 16, gate at `0x60000000`, gate spread set to `0.8`, velocity spread set to `0.4`.
*   **`127_SYNTH_HARD_SYNC_C5.XML` (Oscillator Hard Sync):**
    *   *Osc 1:* Sawtooth wave, 100% Volume.
    *   *Osc 2:* Sawtooth wave, 100% Volume, transposed detune = +12 semitones, cents = +50, with `oscillatorSync="1"` enabled to reset Osc 2's phase on each Osc 1 cycle.
    *   *Recording instructions:* Record a sustained **C5 note (MIDI Note 72)** for 4 seconds. Save the resampled mono wave as `reference_synth_hard_sync_c5.wav`.
*   **`128_SYNTH_DUAL_MOD_C5.XML` (Modulation Matrix Multi-Source Summing):**
    *   *Osc 1:* Sawtooth wave, 100% Volume.
    *   *LFO 1:* Sine wave running at slow speed (`rate = 0x20000000`) targeted to low-pass filter cutoff (`amount = 0x20000000`).
    *   *LFO 2:* Triangle wave running at fast speed (`rate = 0x40000000`) targeted to low-pass filter cutoff (`amount = 0x18000000`). Tests correct summing of multiple modulators on the same destination path.
    *   *Recording instructions:* Record a sustained **C5 note (MIDI Note 72)** for 4 seconds. Save the resampled mono wave as `reference_synth_dual_mod_c5.wav`.
*   **`129_FM_GLIDE_RATIO_C5.XML` (FM Portamento Glide Ratio):**
    *   *Engine Type:* FM mode (2-Operator FM with carrier/modulator ratio = 2.0).
    *   *Portamento:* Slide time configured (`portamento = 0x40000000`). Evaluates whether carrier and modulator frequencies stay in perfect ratio locks during pitch slides.
    *   *Recording instructions:* Record a legato step sequence transition playing **C4 (MIDI Note 60) transitioning to C5 (MIDI Note 72)** with pitch glide enabled. Save the resampled wave as `reference_fm_glide_ratio_c5.wav`.

