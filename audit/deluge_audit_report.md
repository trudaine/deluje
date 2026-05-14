# COMPREHENSIVE DELUGE FIRMWARE TO JAVA PORT AUDIT

**Date:** 2026-05-14
**Firmware source:** `../DelugeFirmware/src/deluge/` (1006 C++ files)
**Java deluge project:** `deluge/src/main/java/org/chuck/deluge/` (210 Java files)
**Java chuck-core project:** `chuck-core/src/main/java/org/chuck/` (347 Java files)

---

## EXECUTIVE SUMMARY

- **Total C++ files:** 1006
- **C++ files with confirmed Java equivalent (full or partial):** ~400 (40%)
- **C++ files with NO Java equivalent:** ~606 (60%)
- **Fully ported subsystems:** DSP primitives (filters, delay, reverb, oscillators, DX7)
- **Partially ported subsystems:** Model layer (Clip, Song), Modulation, MIDI, Engine
- **NOT ported at all:** GUI/UI, HID (hardware), Storage, Memory allocator, Drivers, most model/settings, most playback, clipboard

The port covers essentially all low-level DSP components in the Java firmware layer, and some of the high-level model objects. The user interface, hardware abstraction, MIDI device management, storage/persistence, and most playback logic remain unported.

---

## 1. ROOT FILES (deluge.cpp, deluge.h, extern.h)

| C++ File | Java Counterpart | Status |
|----------|-----------------|--------|
| deluge.cpp | NONE | NOT PORTED. Top-level main loop, task scheduling, button/pad reading, startup, blank song setup, USB host management. Low-level hardware init code. |
| deluge.h | NONE | NOT PORTED. C-linkage extern declarations for main(), midi/gate timer, cluster loading. |
| extern.h | NONE | NOT PORTED. Forward declarations of global extern variables. |

**Unported functions:** mainLoop(), registerTasks(), readButtonsAndPads(), setupBlankSong(), setupStartupSong(), setupOLED(), inputRoutine(), batteryLEDBlink(), isShortPress(), setUIForLoadedSong(), deleteOldSongBeforeLoadingNew()

---

## 2. DSP -- MOSTLY PORTED

### stereo_sample.h
- **Java:** firmware/dsp/StereoSample.java, firmware/dsp/StereoFloatSample.java -- PORTED
- C++ class StereoSample with L/R audio data and operations

### dsp/compressor/
| C++ File | Java | Status |
|----------|------|--------|
| rms_feedback.cpp/.h | firmware/dsp/compressor/RMSFeedbackCompressor.java | PORTED. RMS feedback compressor. |

### dsp/convolution/
| C++ File | Java | Status |
|----------|------|--------|
| impulse_response_processor.h | firmware/dsp/convolution/ImpulseResponseProcessor.java | PORTED |

### dsp/delay/
| C++ File | Java | Status |
|----------|------|--------|
| delay.cpp/.h | firmware/dsp/delay/Delay.java | PORTED. Class includes State sub-struct. |
| delay_buffer.cpp/.h | firmware/dsp/delay/DelayBuffer.java | PORTED. Includes ResampleConfig. |

### dsp/dx/ (DX7 synthesis)
| C++ File | Java | Status |
|----------|------|--------|
| EngineMkI.cpp/.h | engine/dsp/NativeDx7Voice.java, audio/util/Dx7Engine.java | PORTED. sin/log LUT, pitch/level scaling. |
| aligned_buf.h | NONE | NOT PORTED. Aligned memory buffer (hardware). |
| dx7note.cpp/.h | audio/util/Dx7Engine.java | PORTED. Note freq calc, LFO, envelope scaling. |
| engine.cpp/.h | audio/util/Dx7Engine.java | PORTED. Core DX7 voice engine. |
| env.cpp/.h | audio/util/Dx7Engine.java, audio/util/Envelope.java | PORTED. DX7 envelope generator. |
| fm_core.cpp/.h | firmware/dsp/dx/FmCore.java | PORTED. Core FM computation. |
| fm_op_kernel.cpp/.h | firmware/dsp/dx/FmOpKernelVector.java | PORTED. Operator kernel. |
| math_lut.cpp/.h | NativeDx7Voice (LUTs) | PORTED. Sine/cosine LUTs. |
| pitchenv.cpp/.h | Dx7Engine (pitch env) | PORTED. Pitch envelope. |

### dsp/envelope_follower/
| C++ File | Java | Status |
|----------|------|--------|
| absolute_value.cpp/.h | firmware/dsp/envelope_follower/AbsValueFollower.java | PORTED |

### dsp/fft/
| C++ File | Java | Status |
|----------|------|--------|
| fft_config_manager.cpp/.h | firmware/dsp/fft/FFTConfigManager.java | PORTED |

### dsp/filter/
| C++ File | Java | Status |
|----------|------|--------|
| filter.cpp/.h | firmware/dsp/filter/FirmwareFilter.java | PORTED |
| filter_set.cpp/.h | firmware/dsp/filter/FilterSet.java, FilterRoute.java | PORTED |
| hpladder.cpp/.h | firmware/dsp/filter/HpLadderFilter.java | PORTED |
| ladder_components.h | firmware/dsp/filter/BasicFilterComponent.java | PORTED |
| lpladder.cpp/.h | firmware/dsp/filter/LpLadderFilter.java | PORTED |
| svf.cpp/.h | firmware/dsp/filter/SVFilter.java | PORTED |

### dsp/granular/
| C++ File | Java | Status |
|----------|------|--------|
| GranularProcessor.cpp/.h | firmware/dsp/granular/GranularProcessor.java | PORTED |

### dsp/interpolate/
| C++ File | Java | Status |
|----------|------|--------|
| interpolate.cpp/.h | NONE (used implicitly in DelayBuffer/StereoSample) | NOT PORTED as standalone. Linear/cubic interpolation for sample playback. |

### dsp/oscillators/
| C++ File | Java | Status |
|----------|------|--------|
| basic_waves.cpp/.h | firmware/dsp/oscillators/BasicWaves.java | PORTED |
| oscillator.cpp/.h | firmware/dsp/oscillators/Oscillator.java, OscType.java | PORTED |
| sine_osc.cpp/.h | firmware/dsp/oscillators/SineOsc.java | PORTED |

### dsp/reverb/freeverb/
| C++ File | Java | Status |
|----------|------|--------|
| freeverb.cpp | firmware/dsp/reverb/freeverb/Freeverb.java | PORTED |
| tuning.h | firmware/dsp/reverb/ReverbBase.java | PORTED |
| (Comb/Allpass inline) | firmware/dsp/reverb/freeverb/Comb.java, Allpass.java | PORTED |

### dsp/timestretch/
| C++ File | Java | Status |
|----------|------|--------|
| time_stretcher.cpp/.h | firmware/dsp/timestretch/TimeStretcher.java | PORTED |

---

## 3. ENGINE / PROCESSING

### processing/engines/
| C++ File | Java | Status |
|----------|------|--------|
| audio_engine.cpp/.h | firmware/engine/FirmwareAudioEngine.java | PARTIALLY PORTED. Missing: render routines, main audio loop, cluster loading, reverb param updating, slowRoutine. |
| cv_engine.cpp/.h + c_interface.h | NONE | NOT PORTED. CV/gate output engine. Hardware-specific. |

### processing/sound/
| C++ File | Java | Status |
|----------|------|--------|
| sound.cpp/.h | firmware/engine/FirmwareSound.java | PARTIALLY PORTED. Missing: render pipeline, voice management, param interpolation. |
| sound_drum.cpp/.h | model/SoundDrum.java | PARTIALLY PORTED. Missing: full sound rendering. |
| sound_instrument.cpp/.h | firmware/engine/FirmwareSynth.java | PARTIALLY PORTED. Missing: full synthesis pipeline. |

### processing/live/, processing/metronome/, processing/stem_export/
- NOT PORTED. Live input processing, metronome, stem export.

---

## 4. MODEL LAYER

### model/action/
| C++ | Java | Status |
|-----|------|--------|
| (action files) | firmware/model/action/ActionLogger.java | PORTED. Action logging and undo/redo. |

### model/clip/
| C++ File | Java | Status |
|----------|------|--------|
| clip.cpp/.h | firmware/model/Clip.java | PARTIALLY PORTED. Missing: cloneFrom, beginInstance, processCurrentPos, renderAsSingleRow, getParameterAutomation. |
| audio_clip.cpp/.h | firmware/model/AudioClip.java, model/Clip.java | PARTIALLY PORTED. Missing: sample playback guide integration. |
| clip_array.cpp/.h | NONE | NOT PORTED |
| clip_instance.cpp/.h | firmware/model/ClipInstance.java | PORTED |
| clip_instance_vector.cpp/.h | NONE | NOT PORTED |
| clip_minder.cpp/.h | firmware/model/clip/ClipMinder.java | PARTIALLY PORTED |
| instrument_clip.cpp/.h | firmware/model/InstrumentClip.java | PARTIALLY PORTED. Missing: note row management, MIDI recording, rendering. |
| instrument_clip_minder.cpp/.h | NONE | NOT PORTED |

### model/consequence/
| C++ | Java | Status |
|-----|------|--------|
| (consequence files) | firmware/model/consequence/Consequence.java, ConsequenceNoteExistence.java | PARTIALLY PORTED |

### model/drum/
| C++ File | Java | Status |
|----------|------|--------|
| drum.cpp/.h | model/Drum.java | PARTIALLY PORTED. Missing: most MIDI/note handling, MPE. |
| drum_name.cpp/.h | NONE | NOT PORTED |
| gate_drum.cpp/.h | model/GateDrum.java | PARTIALLY PORTED |
| midi_drum.cpp/.h | model/MIDIDrum.java | PARTIALLY PORTED |
| non_audio_drum.cpp/.h | NONE | NOT PORTED |

### model/note/
| C++ File | Java | Status |
|----------|------|--------|
| note.cpp/.h | firmware/model/note/Note.java | PARTIALLY PORTED. Missing: note expression, velocity handling. |
| note_row.cpp/.h | firmware/model/note/NoteRow.java | PARTIALLY PORTED. Missing: iteration, automation, MIDI recording, rendering. |

### model/sample/
| C++ | Java | Status |
|-----|------|--------|
| (many sample files) | firmware/model/sample/SampleCache.java | MINIMALLY PORTED. Only SampleCache exists. Missing: Sample, SampleControls, SamplePlaybackGuide, SampleHolderForClip, AudioFileManager, cluster loading. |

### model/scale/
| C++ | Java | Status |
|-----|------|--------|
| (scale files) | firmware/model/scale/NoteSet.java, ScaleChange.java, ScaleMapper.java | PORTED |

### model/settings/
- NOT PORTED. Flash storage, runtime feature settings, default settings.

### model/song/
| C++ File | Java | Status |
|----------|------|--------|
| song.cpp/.h | firmware/model/Song.java | PARTIALLY PORTED. Missing: clip management, output management, loading/saving, session/arrangement logic. |
| clip_iterators.cpp/.h | NONE | NOT PORTED |

### model/voice/
| C++ File | Java | Status |
|----------|------|--------|
| voice.cpp/.h | firmware/engine/FirmwareVoice.java | PARTIALLY PORTED. Missing: full rendering, note-on/off, voice stealing, param interpolation. |
| voice_sample.cpp/.h | NONE | NOT PORTED |
| voice_sample_playback_guide.cpp/.h | NONE | NOT PORTED |
| voice_unison_part.h | NONE | NOT PORTED |
| voice_unison_part_source.cpp/.h | NONE | NOT PORTED |

### model/mod_controllable/
- NOT PORTED (mod_controllable, mod_controllable_audio, ModFXProcessor, filters/)

### model/instrument/, model/fx/, model/global_effectable/, model/favourite/, model/iterance/, model/midi/

| Directory | Java | Status |
|-----------|------|--------|
| model/global_effectable/ | firmware/engine/GlobalEffectable.java | PARTIALLY PORTED |
| model/iterance/ | firmware/model/iterance/Iterance.java | PORTED |
| model/instrument/ | NONE | NOT PORTED |
| model/fx/ | NONE | NOT PORTED |
| model/favourite/ | NONE | NOT PORTED |
| model/midi/ | NONE | NOT PORTED |
| model/output.h | NONE | NOT PORTED |

---

## 5. MODULATION

| C++ Directory | Java Counterparts | Status |
|---------------|-------------------|--------|
| modulation/arpeggiator.cpp/.h | firmware/modulation/Arpeggiator.java | PORTED |
| modulation/envelope.cpp/.h | firmware/modulation/Envelope.java, engine/dsp/FirmwareAdsr.java, SwitchableAdsr.java | PORTED |
| modulation/lfo.cpp/.h | firmware/modulation/LFO.java | PORTED |
| modulation/automation/ | firmware/modulation/automation/AutoParam.java, ParamNode.java | PORTED |
| modulation/params/ | firmware/modulation/params/Param.java, ParamManager.java | PORTED |
| modulation/patch/ | firmware/modulation/patch/Destination.java, PatchCable.java, PatchCableSet.java, PatchSource.java, Patcher.java | PORTED |
| modulation/sidechain/ | firmware/modulation/sidechain/SideChain.java | PORTED |
| modulation/midi/ | NONE | NOT PORTED |
| modulation/knob.h | NONE | NOT PORTED |

---

## 6. GUI / UI -- MOSTLY NOT PORTED

### gui/views/
| C++ File | Java | Status |
|----------|------|--------|
| view.cpp/.h | firmware/hid/FirmwareView.java | PARTIALLY PORTED. Missing: scrolling, rendering, clipboard. |
| session_view.cpp/.h | firmware/gui/views/SessionView.java, ui/SwingGridPanel.java | PARTIALLY PORTED |
| arranger_view.cpp/.h | firmware/gui/views/ArrangerView.java, ui/SwingArrangerPanel.java | PARTIALLY PORTED |
| instrument_clip_view.cpp/.h | NONE | NOT PORTED |
| audio_clip_view.cpp/.h | NONE | NOT PORTED |
| automation_view.cpp/.h | firmware/gui/views/AutomationView.java, ui/AutomationPanel.java | PARTIALLY PORTED |
| performance_view.cpp/.h | firmware/gui/views/PerformanceView.java, ui/SwingPerformanceViewPanel.java | PARTIALLY PORTED |
| clip_view.cpp/.h, timeline_view.cpp/.h, clip_navigation_timeline_view.cpp/.h | NONE | NOT PORTED |
| PianoRollView.java (firmware/gui/views/) | NONE | NOT PORTED in C++? Java has PianoRollView. |

### gui/ui/
| C++ File | Java | Status |
|----------|------|--------|
| ui.cpp/.h | firmware/hid/FirmwareUI.java | PARTIALLY PORTED |
| sound_editor.cpp/.h | NONE | NOT PORTED |
| audio_recorder.cpp/.h | NONE | NOT PORTED |
| menus.cpp/.h | NONE | NOT PORTED |
| browser/ | firmware/gui/ui/browser/FileBrowser.java | PARTIALLY PORTED |
| keyboard/, load/, save/, rename/ | NONE | NOT PORTED |
| slicer.cpp/.h, sample_marker_editor.cpp/.h | NONE | NOT PORTED |
| qwerty_ui.cpp/.h, root_ui.cpp/.h | NONE | NOT PORTED |

### gui/menu_item/ (FULL HIERARCHY -- NOT PORTED)
- All ~40+ subdirectories: arpeggiator, audio_compressor, automation, battery, bend_range, cv, defaults, delay, dx, envelope, eq, filter, firmware, flash, fx, gate, keyboard, lfo, midi, mod_fx, modulator, monitor, mpe, note, note_row, osc, patch_cable_strength, patched_param, performance_session_view, randomizer, record, reverb, runtime_feature, sample, sequence, shortcuts, sidechain, song, source, source_selection, stem_export, stutter, submenu, swing, sync_level, trigger, unison, unpatched_param, voice

### gui/context_menu/ (NOT PORTED)
- clip_settings, sample_browser, stem_export

### Other gui/ -- NOT PORTED
- gui/colour/, gui/fonts/, gui/l10n/, gui/waveform/

---

## 7. HID (HARDWARE INPUT/OUTPUT)

| C++ File | Java | Status |
|----------|------|--------|
| matrix_driver.cpp/.h | firmware/hid/MatrixDriver.java | PORTED |
| pad.cpp/.h | firmware/hid/Cartesian.java | PARTIALLY PORTED |
| button.cpp/.h | firmware/hid/ActionResult.java | MINIMALLY PORTED |
| buttons.cpp/.h | NONE | NOT PORTED. Shift state, modifier logic. |
| encoder.cpp/.h, encoders.cpp/.h | NONE | NOT PORTED |
| hid_sysex.cpp/.h | NONE | NOT PORTED |
| display/display.cpp/.h | firmware/hid/FirmwareDisplay.java | PARTIALLY PORTED |
| display/oled.cpp/.h | NONE | NOT PORTED |
| display/seven_segment.cpp/.h | NONE | NOT PORTED |
| display/oled_icons.cpp | NONE | NOT PORTED |
| display/numeric_layer/, oled_canvas/ | NONE | NOT PORTED |
| led/indicator_leds.cpp/.h | NONE | NOT PORTED |
| led/pad_leds.cpp/.h | firmware/hid/PadLEDs.java | PARTIALLY PORTED. Missing: animation, rendering, image data. |

---

## 8. IO

### io/debug/ -- NOT PORTED

### io/midi/
| C++ File | Java | Status |
|----------|------|--------|
| midi_device.cpp/.h | midi/MidiDeviceDefinition.java, MidiDeviceDefinitionLoader.java | PARTIALLY PORTED |
| midi_device_manager.cpp/.h | midi/MidiService.java | PARTIALLY PORTED |
| midi_engine.cpp/.h | engine/NativeMidiInputRouter.java, RtMidiInputRouter.java | PARTIALLY PORTED. MIDI routing. |
| midi_follow.cpp/.h | NONE | NOT PORTED. MIDI Follow. |
| midi_takeover.cpp/.h | NONE | NOT PORTED |
| midi_transpose.cpp/.h | NONE | NOT PORTED |
| learned_midi.cpp/.h | NONE | NOT PORTED |
| sysex.cpp/.h | NONE | NOT PORTED |
| cable_types/, device_specific/ | NONE | NOT PORTED |

---

## 9. MEMORY -- NOT PORTED

All files in memory/ directory. General memory allocator, stack management, fixed-size allocators. Hardware-specific.

---

## 10. PLAYBACK

| C++ File | Java | Status |
|----------|------|--------|
| playback_handler.cpp/.h | firmware/playback/PlaybackHandler.java | PARTIALLY PORTED. Missing: routine(), midiRoutine(), slowRoutine(), play/stop/pause, tempo, clock. |
| clock_output_scheduler.cpp/.h | NONE | NOT PORTED |
| playback/mode/arrangement | firmware/playback/Arrangement.java | PARTIALLY PORTED |
| playback/mode/session | NONE | NOT PORTED |

---

## 11. STORAGE -- MOSTLY NOT PORTED

| C++ File | Java | Status |
|----------|------|--------|
| storage_manager.cpp/.h | xml/DelugeXmlParser.java, DelugeXmlExporter.java | PARTIALLY PORTED. XML persistence. SD access layer not ported. |
| flash_storage.cpp/.h | NONE | NOT PORTED |
| file_item.cpp/.h | NONE | NOT PORTED |
| smsysex.cpp/.h | NONE | NOT PORTED |
| Serializer.cpp, Deserializer.cpp, JSONSerializer.cpp, JsonDeserializer.cpp | NONE | NOT PORTED |
| DX7Cartridge.cpp/.h | firmware/storage/dx7/DX7Cartridge.java | PORTED |
| audio/ | NONE | NOT PORTED |
| cluster/ | NONE | NOT PORTED |
| multi_range/ | firmware/storage/multi_range/MultisampleRange.java | PORTED |
| wave_table/ | firmware/storage/wave_table/WaveTable.java, WaveTableBand.java, WaveTableReader.java, WavetableGenerator.java | PORTED |

---

## 12. UTILITIES

| C++ File | Java | Status |
|----------|------|--------|
| string.cpp/.h, d_string.cpp/.h, d_stringbuf.cpp/.h | NONE | NOT PORTED (Java has native strings) |
| functions.cpp/.h | NONE | NOT PORTED. Math utilities. |
| misc.h | NONE | NOT PORTED |
| pack.c/.h | NONE | NOT PORTED |
| fixedpoint.h | NONE | NOT PORTED |
| cfunctions.c/.h | NONE | NOT PORTED |
| comparison.h, const_functions.h, exceptions.h, finally.h | NONE | NOT PORTED |
| semver.cpp/.h | NONE | NOT PORTED |
| firmware_version.cpp/.h.in | NONE | NOT PORTED |
| chainload.cpp/.h | NONE | NOT PORTED |
| waves.cpp/.h | NONE | NOT PORTED |
| algorithm/ | NONE | NOT PORTED |
| container/ (array, hashtable, list, vector) | NONE | NOT PORTED (Java has stdlib) |
| lookuptables/ | firmware/util/LookupTables.java | PORTED |
| phase_increment_fine_tuner.cpp/.h | firmware/util/FirmwareUtils.java (possibly) | UNVERIFIED |

---

## 13. DRIVERS -- NOT PORTED (Hardware-level, expected)

- drivers/dmac/, drivers/mtu/, drivers/oled/, drivers/pic/, drivers/rspi/, drivers/ssi/, drivers/uart/, drivers/usb/userdef/

---

## 14. JAVA-SPECIFIC FILES (No C++ counterpart)

These Java files provide Swing UI, project management, and ChucK integration:

- ui/SwingDelugeApp.java - Main Swing application
- ui/SwingMatrixPanel.java - Pad matrix rendering
- ui/SwingGridPanel.java - Session/clip grid
- ui/SwingArrangerPanel.java - Arranger view
- ui/SwingTopBarPanel.java - Top bar
- ui/SwingVisualizerPanel.java - Audio visualization
- ui/SwingVelocityLanePanel.java - Velocity lane
- ui/SwingProjectSidebarPanel.java - Project sidebar
- ui/SwingSynthConfigDialog.java - Synth config dialog
- ui/SwingKitConfigDialog.java - Kit config dialog
- ui/SwingMasterFxPanel.java - Master FX panel
- ui/SwingChordKeyboardPanel.java - Chord keyboard
- ui/SwingPerformanceViewPanel.java - Performance view
- ui/AlgorithmPanel.java - DX7 algorithm visualization
- ui/ArpPanel.java, OscPanel.java, LfoPanel.java, EnvelopePanel.java, ModulationPanel.java
- ui/Dx7Panel.java, CompressorPanel.java, EqPanel.java, HpfPanel.java, ModFxPanel.java
- ui/AutomationPanel.java, PianoRollComponent.java
- ui/PreferencesDialog.java, TrackInspectorDialog.java
- ui/StepPropertiesDialog.java, BarAutomationDialog.java, EuclideanRhythmDialog.java
- ui/SwingAudioTrackPanel.java
- project/KitSynthSerializer.java, PatternSerializer.java, ProjectSerializer.java, AutoSaveService.java, PreferencesManager.java
- kit/KitAssembler.java
- xml/DelugeXmlValidator.java, DelugeHexMapper.java, DelugeNoteDataMapper.java, FieldBinding.java
- midi/MidiInputRouter.java, MidiFeedbackService.java
- als/AlstoDelugeConverter.java - Ableton Live ALS import
- downloader/ArturiaDownloader.java, DelugeDownloader.java - Sample downloads
- bridge/BridgeContract.java

---

## 15. ENGINE DSP WRAPPERS (Java-specific abstraction)

These are Java adapter classes that route between native ChucK DSP and firmware implementations:

- engine/dsp/SwitchableAdsr.java, SwitchableCompressor.java, SwitchableFilter.java
- engine/dsp/FirmwareAdsr.java, FirmwareCompressor.java, FirmwareDelay.java, FirmwareReverb.java, FirmwareSVFilter.java
- engine/dsp/NativeAdsr.java, NativeCompressor.java, NativeDelay.java, NativeDx7Voice.java, NativeDx7VoiceNative.java, NativeHPF.java, NativeLfo.java, NativeMidiExporter.java, NativeMoogFilter.java, NativeReverb.java, NativeSVFilter.java, NativeSndBuf.java, NativeWavExporter.java
- engine/FirmwareFactory.java - Factory for creating firmware vs native components
- engine/Stutterer.java - Stutter effect

---

## 16. CHUCK-CORE SHARED DSP (Used by deluge port)

Chuck-core provides the ChucK virtual machine, audio analysis, audio file I/O, MIDI, filters, effects, oscillators, and STK instrument models. The deluge firmware port specifically uses:

- audio/util/Dx7Engine.java, Dx7EngineLookupTables.java, Dx7Native.java, Dx7Patch.java -- DX7 synthesis engine
- audio/util/DelugeAdsr.java -- Deluge-specific ADSR
- audio/util/SndBuf.java, WavReader.java, AiffReader.java -- Sample loading
- audio/util/Wavetable.java, MorphingWavetable.java -- Wavetable oscillators
- audio/filter/SVFilter.java -- Shared SV filter
- audio/fx/FreeVerb.java, MVerb.java -- Reverb effects

---

## ASSESSMENT BY CATEGORY

### Fully Ported (100% of major functionality in Java)
1. DSP filters (all 6 C++ filter files -> 8 Java files)
2. DSP delays (delay + delay buffer -> 2 Java files)
3. DSP DX7 (12 C++ files -> 6+ Java files)
4. DSP reverb (freeverb -> 4 Java files)
5. DSP oscillators (3 C++ files -> 5 Java files)
6. DSP granular (1 C++ pair -> 1 Java file)
7. DSP time-stretch (1 C++ pair -> 1 Java file)
8. DSP compressor (1 C++ pair -> 1 Java file)
9. DSP envelope follower (1 C++ pair -> 1 Java file)
10. DSP convolution (1 C++ file -> 1 Java file)
11. DSP FFT config (1 C++ pair -> 1 Java file)
12. Stereo sample (1 C++ header -> 2 Java files)
13. Lookup tables (6+ C++ files -> 1 Java file)
14. Wavetable storage (4+ C++ files -> 5 Java files)
15. Scale system (model/scale C++ files -> 3 Java files)
16. Iterance -> 1 Java file
17. Modulation: Arpeggiator, Envelope, LFO, Automation, Params, Patch, Sidechain

### Partially Ported (structure exists, logic missing)
1. AudioEngine (FirmwareAudioEngine.java) -- needs render loop
2. Sound (FirmwareSound.java) -- needs render pipeline
3. Voice (FirmwareVoice.java) -- needs full rendering + management
4. Song (Song.java) -- needs clip/output management + load/save
5. Clip hierarchy -- needs XML serialization, playback logic
6. Note/NoteRow -- needs iteration, automation
7. Drum hierarchy -- needs MIDI/note handling
8. PlaybackHandler -- needs play/stop/tempo/clock routines
9. MIDI engine/routing -- needs device manager, follow, takeover
10. Storage manager -- needs SD/cluster layer
11. Display -- needs OLED/7-segment specifics
12. Pad LEDs -- needs animation/rendering
13. Views (Session, Arranger, Automation, Performance) -- basic structure only

### NOT Ported (est. 606 C++ files)
1. ALL gui/menu_item/ files (~hundreds of C++ files)
2. ALL gui/context_menu/ files
3. ALL gui/ui/ files (sound_editor, audio_recorder, menus, keyboard, load, save, rename, browser details)
4. ALL gui/colour/, fonts/, l10n/, waveform/ files
5. ALL gui/ui/browser/ detail files
6. ALL drivers/ files (8 subdirectories)
7. ALL memory/ files (general memory allocator)
8. ALL io/debug/ files
9. ALL io/midi/cable_types/, device_specific/ files
10. ALL model/settings/ files
11. ALL model/instrument/ files
12. ALL model/fx/ files (reverb/delay FX settings)
13. ALL model/favourite/ files
14. ALL model/midi/ files
15. ALL model/mod_controllable/ detail files
16. ALL model/voice/ detail files (voice_sample, unison, playback guide)
17. ALL model/sample/ detail files (except SampleCache)
18. ALL model/clip/detail files (clip_array, clip_instance_vector, instrument_clip_minder)
19. MOST model/consequence/ files (many consequence types)
20. ALL playback/mode/session files
21. ALL clock_output_scheduler files
22. ALL processing/live/, metronome/, stem_export/ files
23. MOST storage/ files (flash, file_item, serializers, audio, cluster)
24. ALL util/algorithm/, container/, version/, chainload files
25. ALL hid/buttons, encoder, display (detailed), led (indicator) files
26. ALL hid/display/numeric_layer/, oled_canvas/ files
27. ALL gui/menu_item/ subdirectory files (~40+ subdirectories)
28. ALL version/ files

### Priority Gaps for Functionality
1. **Audio rendering pipeline** -- AudioEngine render routine is the single most critical gap. Without it, no sound is produced.
2. **Playback engine** -- playbackHandler::routine() drives the entire clock/sequencer/playback system.
3. **Session mode** -- clip launching, queuing, recording to session.
4. **Sound rendering** -- Sound::render() and Voice::render() produce actual audio from models.
5. **Storage/serialization** -- XML loading/saving of songs, kits, synth presets.
