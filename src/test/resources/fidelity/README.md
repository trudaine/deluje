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

## 4. Running the Waveform Parity Comparison Tool
You can execute the automated phase-aligning waveform comparison tool directly against these local Git-tracked resources from the command line:

```bash
# Force compile and run the default C5 Dry Sawtooth (Patch A) comparison:
mvn test-compile && mvn -pl deluge exec:java -Dexec.classpathScope="test" -Dexec.mainClass="org.chuck.deluge.reproduce.CompareAudioParity"
```

The tool will auto-align zero-crossing phases, perform peak-normalization, and print:
1. Aligned transient delay onset offsets.
2. 100ms drift-free window shape cross-correlation indexes (Target >= 90%).
3. Active amplitude levels, RMS ratios, and fundamental pitch frequency correlation checks.
