# DelugeFirmware C++ to Java Port: Functional Domain Audit

**Date**: 2026-05-14
**Scope**: C++ source at `DelugeFirmware/src/deluge/` vs Java port at `chuckjava/deluge/src/main/java/org/chuck/deluge/`
**Method**: Side-by-side reading of every core class, tracing the render pipeline, analyzing class hierarchies.

---

## 1. Audio DSP

### Oscillators — Mostly Matched
C++ has `OscType` with 13 types (SINE through INPUT_STEREO), `Oscillator` with `renderOsc()` (table-lookup based on `OscType`), `WaveTable`, and `Sample` (for sample-based oscillators). Java `OscType.java` matches the enum 1:1. `Oscillator.renderOsc()` exists and is structurally similar. `SineOsc` and `BasicWaves` are present. **Missing**: `WaveTable` oscillator (wavetable interpolation), `Sample` oscillator (disk-streaming sample playback), `DX7` FM oscillator (though FmCore/FmOpKernelVector exist as separate DSP utilities).

### Filters — Good Coverage
C++ filter pipeline: `LpLadderFilter` (4-pole Moog ladder), `SVFilter` (state-variable for band/notch), `HpLadderFilter` (high-pass ladder), plus `FilterSet` which applies low-pass + high-pass in series. Java has all three filter types (`LpLadderFilter.java`, `SVFilter.java`, `HpLadderFilter.java`) and `FilterSet.java`. The `render()` methods match C++ structure. **Missing**: `renderLongStereo()` variants on `FilterSet`, the "24dB drive" modes on transistor ladder (C++ has TRANSISTOR_24DB_DRIVE in addition to 12/24).

### Reverb — Matched
Freeverb implementation is complete: `Freeverb.java`, `Comb.java`, `Allpass.java` with fixed-point arithmetic matching C++. `ReverbBase.java` provides the abstract foundation. **No gaps detected**.

### Delay — Matched
`Delay.java` and `DelayBuffer.java` present. The C++ delay line with feedback, ping-pong, and tap filtering is ported. **No gaps detected**.

### Compressor — Matched
`RMSFeedbackCompressor.java` with `renderVolNeutral()` matches C++ RMS compressor. **No gaps detected**.

### Granular — Partial
`GranularProcessor.java` exists. C++ uses this for time-stretching on audio clips. Java has the processor class but it's **not wired into any render pipeline** — no clip or instrument calls it.

### Time Stretch — Partial
`TimeStretcher.java` exists. Same status as Granular: present as a class, **not integrated** into audio clip playback.

### Convolution/FFT — Partial
`ImpulseResponseProcessor.java` and `FFTConfigManager.java` exist. These support cabinet simulation and spectral processing. Present but **not wired** into any signal chain.

### Sidechain — Matched
`SideChain.java` with envelope follower and ducking logic matches C++. Fully ported.

### Envelope Follower — Matched
`AbsValueFollower.java` present. Used for modulation source ENVELOPE_FOLLOWER. Matches C++.

---

## 2. Model / Data Layer

### Sound — Critically Incomplete
C++ `Sound` class (4977 lines .cpp) is the heart of synthesis. It owns: sources (2), global LFOs (2), patcher, arpeggiator, reverb config, delay config, stutter config, sidechain, mod FX, EQ, compressor, unison settings, SRR/bitcrush, synth mode, filter routing. Java `FirmwareSound.java` (~60 lines) extends `GlobalEffectable` and is **extremely sparse**:
- Has voices list, 2 global LFOs, maxPolyphony — correct structure
- **Missing**: unison parameters, stutter, mod FX, EQ, sidechain integration (class exists but unconnected), synth mode field (SOUND vs KIT), filter routing configuration, reverb send amount, panning, portamento mode

### Voice — Critically Incomplete
C++ `Voice` class (2527 lines) is the per-note renderer. It owns: 6 envelopes (kNumEnvelopes), 4 LFOs (lfo1-lfo4, though lfo2/lfo4 are per-voice), filterSet, portamento, unison parts, MPE expression smoothing, FM with feedback. Java `FirmwareVoice.java` (142 lines):
- Has 4 envelopes, 2 LFOs — **missing 2 LFOs and 2 envelopes**
- **No portamento** (per-voice glide)
- **No unison part rendering** (stacked voices)
- **No MPE expression smoothing**
- **No FM rendering** (FmCore class exists but is not called from voice)
- **No auto-release** (voice stealing with release stage)
- `render()` only uses envelope 0 and dummy LFO rates — **heavily stubbed**

### ModControllableAudio — Mostly Missing
C++ `ModControllableAudio` has: delay, compressor, granular processor, stutter, sidechain, mod FX, EQ, SRR, bitcrush — all wired with `processFX()`, `processSRRAndBitcrushing()`, `processReverbSendAndVolume()`. Java `GlobalEffectable.java` (76 lines):
- Has LpLadderFilter, RMSFeedbackCompressor, Delay — correct types
- Implements `processReverbSendAndVolume()` — **matched**
- **Missing**: `processFilters()`, `processFX()` (mod FX), `processSRRAndBitcrushing()`, `processStutter()`, EQ processing
- The compressor and delay exist as fields but are **not called** from the sound's render pipeline

### Song — Drastically Simplified
C++ `Song` (6120 lines, 485 header) manages: currentSong pointer singleton, output instances (instruments), session clips, arranger clips, clip nesting (section), swing (interval/groove/gate/shuffle), scale (root/mode/notes), MPE settings (bend range), global FX, quantization, play position, loop points, time signature. Java `Song.java` (~65 lines):
- Has clips list, tempo, rootNote, ParamManager, swing settings — correct basics
- **Missing**: outputs list (instrument-to-clip mapping), arranger clips, section/clip nesting, scale system, MPE settings, global FX settings, currentPos/bpm tracking, loop points, time signature

### Clip — Partial
C++ `Clip` (1177 lines) is base for InstrumentClip and AudioClip. Java `Clip.java` is an interface/abstract with basic positioning. `InstrumentClip.java` has NoteRow list, arp settings, loop length. **Missing**: `AudioClip` (sample-based clip with audio recording/playback, time-stretching), clip sequencing state machine (pending over-dub, solo, mute, arm).

### Note/NoteRow — Good Coverage
Java `Note.java` and `NoteRow.java` match C++ counterparts. Note has: pitch, velocity, probability, velocity deviation, iteration. NoteRow has: sequence direction, mute, drum config. Good coverage.

### Scale — Partial
Java has `ScaleMapper.java`, `ScaleChange.java`, `NoteSet.java` — basic scale mapping. C++ `Scale` has full note-name mapping, scale patterns, root note, mode, and quantization. **Missing**: scale mode handling, keyboard layout mapping, custom scale support.

---

## 3. Playback Engine

### AudioEngine — Very Simplified
C++ `AudioEngine` orchestrates the full render callback: rendering sounds, reverb, delay, sidechain, compressor, master volume, interleave handling, `renderInStereo` flag. Java `FirmwareAudioEngine.java` (84 lines):
- Creates master/delay/reverb buffers — correct
- Iterates sounds and calls `renderOutput` — correct
- Applies master reverb, delay, compressor, volume — correct
- **Missing**: `renderInStereo` flag logic, interleave/mono-summing, render thread synchronization, sample-rate change handling, audition/input monitoring

### SequencerClock / PlaybackHandler — Stubbed
C++ `PlaybackHandler` (multi-file) manages: play/stop/record, swing clock, quantization, tick counting, MIDI clock sync, session/arrangement mode switching, count-in, double/halve tempo. Java `PlaybackHandler.java` (84 lines):
- Has arrangement mode toggle, start/stop, advanceTicks — correct basics
- **Missing**: swing processing (fully — the field exists in Song but no code uses it), record arming, quantization of note-on events, MIDI clock sync, count-in, double/halve tempo, session view switching

### Sequencer stepping
Java `DelugeEngineDSL.java` wraps the SequencerClock with `renderBlock()`/`advanceTicks()` loop. It calls `PlaybackHandler.advanceTicks()` which iterates clips. This provides functional playback but without swing, quantization, or record.

---

## 4. Modulation System

### ParamManager — Simplified
C++ `ParamManager` has 5 `ParamCollectionSummary` slots: `PatchedParamSet`, `UnpatchedParamSet`, `ExpressionParamSet`, `PatchCableSet`, `MIDIParamCollection`. Each wraps actual parameter storage with automation. Methods include: `processCurrentPos()`, `tickSamples()`, `setPlayPos()`, `getParamSet()`, `getPatchCableSet()`. Java `ParamManager.java` (57 lines):
- Has flat `List<AutoParam>` and `PatchCableSet`
- `processCurrentPos()` exists but is simplified (no model/param stack)
- **Missing**: `PatchedParamSet` (patchable params), `UnpatchedParamSet` (non-patchable + automation), `ExpressionParamSet` (MPE expressions), `MIDIParamCollection` (MIDI CC mapping), the 5-collection abstraction entirely

### Patcher — Matched
C++ `Patcher` applies patch cable modulation: reads source values, looks up cable destinations, scales by cable amount, applies to param final values. Java `Patcher.java` does the same. **Matched correctly**.

### PatchCableSet — Matched
Both sides have: array of PatchCable objects, each with source, destination, strength (`midiAndAftertouchUsingParameter`). **Matched**.

### AutoParam / ParamNode — Matched
Java `AutoParam.java` and `ParamNode.java` match C++ automation system: nodes sorted by position, interpolation modes, tick-based advancement. **Good coverage**.

### LFO — Partial
C++ LFO has `LFOConfig` with waveType, syncType, syncLevel, render wave selection. Java `LFO.java` has waveform renderers (SINE through WARBLER) but **LFOConfig is missing** — no sync type/level, no per-LFO config storage. Uses hardcoded `LFOType.TRIANGLE` and dummy rate.

### Envelope — Matched
Java `Envelope.java` matches C++: attack/decay/sustain/release stages, fixed-point time calculation, retrigger handling. **One gap**: C++ has comparator thresholds for stage transitions that Java's `render()` may not implement identically.

### Arpeggiator — Partial
`Arpeggiator.java` exists with basic note sequencing, octave cycling, gate modes. **Missing**: pattern modes (up/down/converge/diverge/etc.), latch mode, MPE integration.

---

## 5. Storage / IO

### ALS Import — New Work
Java has `deluge/src/main/java/org/chuck/deluge/als/` with multiple ALS (Ableton Live Set) parsing classes. This is **entirely new development** not present in the C++ codebase. C++ firmware loads songs from its own binary format (`XML`-based song file). The ALS parser is a separate effort to import from Ableton.

### Audio Clip Playback (Sample loading)
C++ has `Sample` and `SampleCache` for loading/serving audio data, `AudioClip` for sample-backed clips, `TimeStretcher` for warping. Java has `SampleCache.java` and the `AudioClip.java` interface but **no actual sample file reading or buffer management** wired into playback. A `SndBuf` exists in `chuck-core` but it's not integrated into the Deluge port's render pipeline.

### File System Abstraction
The C++ firmware has `disk/` directory with SD card filesystem abstraction, binary serialization/deserialization for all model objects. **None of this is ported** — there is no `disk/` or `storage/` equivalent in the Java deluge port. The Swing UI drives everything from Java objects without persistence.

---

## 6. GUI / OLED

### Swing UI — Ground-Up Reimplementation
Java has an extensive Swing UI at `deluge/src/main/java/org/chuck/deluge/gui/` and `deluge/src/main/java/org/chuck/deluge/ui/`. This is **not a port** of the C++ OLED/LCD code — it's a full Swing-based GUI that emulates:
- 7-segment LED display (multiplexed digits)
- Main pad grid (16x8 velocity-sensitive pads)
- Encoder knobs with acceleration
- Side/up/down/select buttons
- LCD waveform/parameter display

This replaces the physical OLED + 7-segment + button matrix with a desktop emulation.

### C++ GUI Code — Not Ported
The actual C++ OLED rendering (`hid/oled/`), LCD rendering (`hid/led/`), button handling (`hid/buttons/`), encoder handling (`hid/encoders/`), and menu system (`hid/menu/`) have **not been ported** class-by-class. Instead, the Swing UI provides equivalent visual functionality through Java Swing components.

### MIDI Hardware layer — Not Ported
C++ MIDI handling (`hid/midi/`) with MIDI device management, message parsing, and routing is not ported. The Swing UI does not emulate MIDI I/O.

---

## Summary

| Domain | Ported | Partial | Missing |
|--------|--------|---------|---------|
| **Oscillators** | OscType enum, basic SineOsc, Oscillator.renderOsc | WaveTable oscillator, DX7 oscillator integrated | Sample oscillator, input oscillators (INPUT_L/R/STEREO) |
| **Filters** | LpLadder, SVFilter, HpLadder, FilterSet | renderLongStereo variants, 24dB drive modes | — |
| **Reverb** | Freeverb (full) | — | — |
| **Delay** | Delay (full) | — | — |
| **Compressor** | RMSFeedbackCompressor (full) | — | — |
| **Modulation** | Patcher, PatchCableSet, AutoParam, Envelope | LFO (no LFOConfig), Arpeggiator (missing patterns) | ParamManager (5-collection structure), ExpressionParamSet, MIDIParamCollection |
| **Sound render** | — | GlobalEffectable (partial FX chain) | Unison, stutter, mod FX, EQ, SRR/bitcrush, sidechain wiring |
| **Voice render** | — | Basic structure (envelopes, LFOs, patcher, oscs) | Portamento, unison, FM, MPE, auto-release, 2 extra envelopes/LFOs |
| **Model (Song/Clip)** | Basic Clip, InstrumentClip, Note, NoteRow | Song (missing scale, MPE, arranger clips, global FX, outputs) | AudioClip, Song persistence, full output/instrument hierarchy |
| **Playback** | Basic render loop (AE -> Sound -> Voice) | Swing processing (unimplemented), quantization | Record arming, MIDI clock sync, session mode, count-in |
| **Storage/IO** | ALS import (new dev) | — | Song file binary format, SD card abstraction, audio sample loading |
| **GUI/OLED** | Swing UI (full emulation) | — | C++ HID layer (not ported by design) |
