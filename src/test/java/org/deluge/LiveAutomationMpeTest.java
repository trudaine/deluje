package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Param;
import org.deluge.modulation.patch.PatchSource;
import org.junit.jupiter.api.Test;

public class LiveAutomationMpeTest {

  @Test
  void testVoiceMpeSourcesAndPitchBend() {
    FirmwareSound sound = new FirmwareSound();

    // Trigger MPE note-on on MIDI channel 3
    sound.triggerNote(60, 100, 3);
    assertFalse(sound.fw2Sound.voices.isEmpty());

    org.deluge.firmware2.Voice voice = sound.fw2Sound.voices.get(0);
    // C: inputCharacteristics[CHANNEL] = fromMidiChannel
    assertEquals(3, voice.inputCharacteristics[1]);
    // C: mpeValues are null (not passed in basic trigger), defaults to 0
    assertEquals(0, voice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(0, voice.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0, voice.localExpressionSourceValuesBeforeSmoothing[1]);

    // Apply MPE bend/timbre/pressure updates to channel 3
    sound.mpePitchBend(3, 10000); // Sharp pitch bend
    sound.mpePressure(3, 80); // Timbre pressure
    sound.mpeTimbre(3, 110); // Filter slide

    // C: expressionEventImmediate sets localExpressionSourceValuesBeforeSmoothing with correct C++
    // scaling
    assertEquals((10000 - 8192) << 18, voice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(80 << 24, voice.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals((110 - 64) << 25, voice.localExpressionSourceValuesBeforeSmoothing[1]);

    // C: combineExpressionValues: (mono>>1 + voice>>1) << 1
    int pressureSource = PatchSource.AFTERTOUCH.ordinal();
    int timbreSource = PatchSource.Y.ordinal();
    assertEquals(80 << 24, voice.sourceValues[pressureSource]);
    assertEquals((110 - 64) << 25, voice.sourceValues[timbreSource]);

    // Release note on MPE channel 3
    sound.releaseNote(60, 3);
  }

  @Test
  void testSubDrumStepAutomation() {
    // Instantiate a sub-drum lane instrument model
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("KIT CLIP", 8, 16);
    // Write LPF frequency step automation for drum lane index 2 at step 4
    clip.setRowAutomation(2, "lpfFrequency", 4, 0.75f);

    // Verify rowAutomationData is populated
    float[] array = clip.getRowAutomation(2, "lpfFrequency");
    assertNotNull(array);
    assertEquals(0.75f, array[4]);

    // Build sub-drum sound and map automations
    FirmwareSound drumSound = new FirmwareSound();
    drumSound.isDrum = true;

    // Map row automation
    java.util.Map<String, float[]> rowAutos = clip.getNoteRowsMap().get(2).getRowAutomation();
    assertNotNull(rowAutos);
    float[] lpfArray = rowAutos.get("lpfFrequency");
    assertNotNull(lpfArray);

    for (int s = 0; s < lpfArray.length; s++) {
      if (lpfArray[s] > 0.0f) {
        int q31Val = (int) (lpfArray[s] * 2147483647.0);
        int pos = s * 24; // 24 ticks per step
        drumSound.paramManager.recordParamValue(Param.LOCAL_LPF_FREQ, q31Val, pos);
      }
    }

    // Process play position at tick index 96 (step 4, pos = 4 * 24 = 96)
    boolean didPingpong = false;
    drumSound.paramManager.processCurrentPos(96, 16 * 24, false, didPingpong, true);

    // Verify the param manager's automated value updates to Q31 space target!
    org.deluge.modulation.automation.AutoParam ap =
        drumSound.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(ap);
    assertEquals((int) (0.75 * 2147483647.0), ap.currentValue);
  }

  @Test
  void testFmModulatorsRetriggerPhases() {
    FirmwareSound sound = new FirmwareSound();
    sound.setSynthMode(org.deluge.engine.FirmwareSound.SynthMode.FM);
    sound.mod1RetrigPhase = 90; // 90 degrees
    sound.mod2RetrigPhase = 180; // 180 degrees

    // Trigger note-on
    sound.triggerNote(60, 100, 0);
    assertFalse(sound.fw2Sound.voices.isEmpty());

    org.deluge.firmware2.Voice voice = sound.fw2Sound.voices.get(0);
    // Note: firmware2 Voice stores modulator retrigger phases in modulatorPhase[] directly.
    // 90 degrees in Q31 = 90 * (2^31 / 360) ≈ 536870911
    // 180 degrees in Q31 = 180 * (2^31 / 360) ≈ 1073741823
    // The C firmware sets these during noteOn via retrigger phases.
    // Currently the bridge doesn't pass modulatorRetriggerPhase — this test documents the gap.
  }

  @Test
  void testMidiRealtimeTransportControls() {
    BridgeContract bridge = new BridgeContract();

    org.deluge.playback.PlaybackHandler playbackHandler = new org.deluge.playback.PlaybackHandler();
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, playbackHandler);

    org.deluge.midi.MidiInputRouter router = new org.deluge.midi.MidiInputRouter(bridge);
    org.deluge.midi.MidiService service = new org.deluge.midi.MidiService(bridge, router);

    assertFalse(playbackHandler.isPlaying()); // initial stop state!

    // Send Start message via active service engine callbacks
    service.getEngine().midiMessageReceived(org.deluge.midi.MIDIMessage.start(), "TEST");
    assertTrue(playbackHandler.isPlaying()); // transport starts playing!

    // Send Stop message
    service.getEngine().midiMessageReceived(org.deluge.midi.MIDIMessage.stop(), "TEST");
    assertFalse(playbackHandler.isPlaying()); // transport stops!

    // Send Continue message
    service.getEngine().midiMessageReceived(org.deluge.midi.MIDIMessage.continueMsg(), "TEST");
    assertTrue(playbackHandler.isPlaying()); // transport resumes!

    // Shutdown VM cleanly
    bridge.shutdown();
  }

  @Test
  void testMidiTakeoverPickupMode() {
    org.deluge.midi.MidiTakeover takeover = new org.deluge.midi.MidiTakeover();
    takeover.setMode(org.deluge.midi.MidiTakeover.Mode.PICKUP);

    // Virtual level is 80
    int virtual = 80;

    // First hardware move at 20 (no previous history) -> registers history and returns -1 (blocked)
    int first = takeover.process(1, 20, virtual);
    assertEquals(-1, first);

    // Hardware moves to 50 (still below 80) -> returns -1 (blocked)
    int second = takeover.process(1, 50, virtual);
    assertEquals(-1, second);

    // Hardware moves to 85 (crosses 80!) -> PICKED UP! Returns hardware value 85!
    int third = takeover.process(1, 85, virtual);
    assertEquals(85, third);

    // Hardware continues to 90 -> returns 90!
    int fourth = takeover.process(1, 90, 85);
    assertEquals(90, fourth);
  }

  @Test
  void testMidiTakeoverScaleMode() {
    org.deluge.midi.MidiTakeover takeover = new org.deluge.midi.MidiTakeover();
    takeover.setMode(org.deluge.midi.MidiTakeover.Mode.SCALE);

    // Virtual level is 100
    int virtual = 100;

    // First hardware move at 20 -> registers history and returns -1 (blocked)
    int first = takeover.process(1, 20, virtual);
    assertEquals(-1, first);

    // Hardware moves to 30 (+10 increase) -> scales value up smoothly!
    // Since previous was registered at 20, hardwareChange = 10.
    int second = takeover.process(1, 30, virtual);
    assertTrue(second > 100 && second < 127);

    // Hardware moves to 127 (limit) -> virtual reaches 127 exactly!
    int third = takeover.process(1, 127, second);
    assertEquals(127, third);
  }

  @Test
  void testSynthGridRowChromaticPitchScaling() {
    FirmwareSound sound = new FirmwareSound();
    sound.setSynthMode(org.deluge.engine.FirmwareSound.SynthMode.SUBTRACTIVE);

    // Simulate clicking a grid pad row vertically in CLIP view mode:
    // Bottom row is index 23 (e.g. C3/MIDI 60)
    int bottomRow = 23;
    int rowAbove = 22;

    // Calculate MIDI note values exactly like SwingGridPanel.java:1132:
    int pitchBottom = ((24 - 1) - bottomRow) + 60;
    int pitchAbove = ((24 - 1) - rowAbove) + 60;

    // Assert that the row ABOVE maps to a higher MIDI note value (like a piano keyboard ascending):
    assertTrue(pitchAbove > pitchBottom);
    assertEquals(60, pitchBottom);
    assertEquals(61, pitchAbove);

    // Convert notes to active audio engine frequency phase increments:
    int pIncBottom = FirmwareSound.noteToPhaseInc(pitchBottom);
    int pIncAbove = FirmwareSound.noteToPhaseInc(pitchAbove);

    // Assert that the frequency phase increment is harmonically higher (higher frequency):
    assertTrue(pIncAbove > pIncBottom);

    // Trigger note-on events on the sound engine:
    sound.triggerNote(pitchBottom, 127);
    sound.triggerNote(pitchAbove, 127);
    assertEquals(2, sound.fw2Sound.voices.size());

    // Assert that the allocated voices have their correct notes and are playing their respective
    // frequencies:
    org.deluge.firmware2.Voice voiceBottom = sound.fw2Sound.voices.get(0);
    org.deluge.firmware2.Voice voiceAbove = sound.fw2Sound.voices.get(1);
    assertEquals(pitchBottom, voiceBottom.note);
    assertEquals(pitchAbove, voiceAbove.note);
  }

  @Test
  void testMpePitchBendModulatesFrequency() {
    FirmwareSound sound = new FirmwareSound();
    sound.setSynthMode(org.deluge.engine.FirmwareSound.SynthMode.SUBTRACTIVE);

    // Trigger a note on MIDI Channel 3 (MPE voice)
    sound.triggerNote(60, 100, 3);
    assertFalse(sound.fw2Sound.voices.isEmpty());

    org.deluge.firmware2.Voice voice = sound.fw2Sound.voices.get(0);

    // Render 1 block of 128 samples to initialize the baseline phase increments
    int[] buffer = new int[128 * 2];
    sound.fw2Sound.renderInternal(buffer, 128, null);

    // Without pitch bend, overallPitchAdjust should be neutral (16777216 = 1 << 24)
    assertEquals(16777216, voice.overallPitchAdjust, "Initial pitch adjust must be neutral");

    // Apply positive MPE Pitch Bend on Channel 3 (newValue = 12000, > 8192)
    sound.mpePitchBend(3, 12000);

    // Render another block to let the voice recalculate overallPitchAdjust and update phase
    // increments
    sound.fw2Sound.renderInternal(buffer, 128, null);

    assertTrue(
        voice.overallPitchAdjust > 16777216,
        "Pitch bend up must increase the overall pitch adjustment factor");

    // Apply negative MPE Pitch Bend on Channel 3 (newValue = 4000, < 8192)
    sound.mpePitchBend(3, 4000);

    // Render another block
    sound.fw2Sound.renderInternal(buffer, 128, null);

    assertTrue(
        voice.overallPitchAdjust < 16777216,
        "Pitch bend down must decrease the overall pitch adjustment factor");

    // Release note
    sound.releaseNote(60, 3);
  }

  @Test
  void testSynthStepAutomationCompilation() {
    // 1. Setup SynthTrackModel with automation
    org.deluge.model.SynthTrackModel model = new org.deluge.model.SynthTrackModel("TESTSYNTH");
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("SYNTH CLIP", 1, 16);
    model.getClips().add(clip);
    model.setActiveClipIndex(0);

    // Set LPF frequency automation at step 5
    clip.setAutomation("lpfFrequency", 5, 0.65f);

    // 2. Compile to FirmwareSound
    org.deluge.engine.FirmwareSound sound = new org.deluge.engine.FirmwareSound();
    org.deluge.engine.FirmwareFactory.applyModelToLiveSound(model, sound);

    // Verify it is compiled in paramManager
    var ap = sound.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(ap);
    assertEquals(1, ap.nodes.size());
    assertEquals(5 * 24, ap.nodes.get(0).pos);
    assertEquals((int) (0.65f * 2147483647.0), ap.nodes.get(0).value);

    // 3. Process play position at tick index 120 (step 5, pos = 5 * 24 = 120)
    sound.paramManager.processCurrentPos(120, 16 * 24, false, false, true);
    assertEquals((int) (0.65f * 2147483647.0), ap.currentValue);

    // 4. Run syncParamsToFw2 and verify it is written to patchedParamValues
    sound.syncParamsToFw2();
    assertEquals(ap.currentValue, sound.fw2Sound.patchedParamValues[Param.LOCAL_LPF_FREQ]);
  }

  @Test
  void testLiveAutomationRecording() {
    // 1. Setup Bridge, Project, and PlaybackHandler
    BridgeContract bridge = new BridgeContract();
    org.deluge.hid.BridgeHolder.setBridge(bridge);

    org.deluge.model.ProjectModel project = new org.deluge.model.ProjectModel();
    org.deluge.model.SynthTrackModel track = new org.deluge.model.SynthTrackModel("TESTSYNTH");
    org.deluge.model.ClipModel clip = new org.deluge.model.ClipModel("SYNTH CLIP", 1, 16);
    track.getClips().add(clip);
    track.setActiveClipIndex(0);
    project.getTracks().add(track);

    org.deluge.playback.PlaybackHandler playbackHandler = new org.deluge.playback.PlaybackHandler();
    playbackHandler.setProject(project);
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, playbackHandler);

    // 2. Start transport and arm live recording
    playbackHandler.start();
    bridge.setGlobalInt(BridgeContract.G_PLAY, 1L);
    org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = true;
    assertTrue(playbackHandler.isPlaying());
    assertTrue(org.deluge.ui.SwingGridPanel.isLiveRecordModeActive);

    // Advance to step 3 (3 * 24 = 72 ticks)
    playbackHandler.advanceTicks(72);
    bridge.setGlobalInt(BridgeContract.G_CURRENT_STEP, 3L);
    assertEquals(3, bridge.getGlobalInt(BridgeContract.G_CURRENT_STEP));

    // 3. Simulate turning the filter cutoff knob (e.g. updating LPF freq to 4000Hz)
    org.deluge.engine.FirmwareSound sound = new org.deluge.engine.FirmwareSound();
    // Pre-populate parameter neutral value to prevent false matching
    sound.paramNeutralValues[Param.LOCAL_LPF_FREQ] = 0;

    track.setLpfFreq(4000.0f);
    // Apply changes - this will trigger our new live automation recording logic!
    org.deluge.engine.FirmwareFactory.applyModelToLiveSound(track, sound);

    // 4. Verify automation is written to the clip and paramManager at step 3!
    float[] automationArray = clip.getAutomationArray("lpfFrequency");
    assertNotNull(automationArray);
    assertEquals(4000.0f / 20000.0f, automationArray[3]);

    var ap = sound.paramManager.getAutomatedParam(Param.LOCAL_LPF_FREQ);
    assertNotNull(ap);
    assertEquals(1, ap.nodes.size());
    assertEquals(3 * 24, ap.nodes.get(0).pos);
    assertEquals((int) ((4000.0f / 20000.0f) * 2147483647.0), ap.nodes.get(0).value);

    // Reset state and shutdown
    org.deluge.ui.SwingGridPanel.isLiveRecordModeActive = false;
    bridge.shutdown();
  }
}
