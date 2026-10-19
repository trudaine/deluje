package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.BridgeContract;
import org.deluge.engine.FirmwareKit;
import org.deluge.engine.FirmwareSound;
import org.deluge.midi.MIDIMessage;
import org.deluge.midi.MidiInputRouter;
import org.deluge.midi.MidiService;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.playback.PlaybackHandler;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

/**
 * Verifies C++ parity for monophonic and polyphonic X/Y/Z expression smoothing against {@code
 * sound_instrument.cpp:321-364}, {@code sound_drum.cpp:74-91}, {@code sound.cpp:2635}, and {@code
 * voice.cpp:333, 783-808}.
 */
class ExpressionSmoothingParityTest {

  @BeforeEach
  void resetNoiseSeed() {
    Functions.resetNoiseSeed();
  }

  @Test
  void testMonophonicExpressionSmoothsAcrossBlocksAndResetsSynthFlag() {
    Sound sound = new Sound();
    sound.triggerNote(60, 100, 1);
    sound.triggerNote(64, 100, 1);
    assertEquals(2, sound.voices.size());

    Voice v0 = sound.voices.get(0);
    Voice v1 = sound.voices.get(1);
    int xIdx = PatchSource.X.ordinal();
    assertEquals(0, v0.sourceValues[xIdx]);
    assertEquals(0, v1.sourceValues[xIdx]);

    int targetX = 0x40000000;
    sound.monophonicExpressionEvent(targetX, 0);

    // C sound_instrument.cpp:321-324 — monophonicExpressionEvent only sets the synth-level flag and
    // target; voices do not jump before renderInternal.
    assertEquals(1, sound.expressionSourcesChangedAtSynthLevel);
    assertEquals(targetX, sound.monophonicExpressionValues[0]);
    assertEquals(0, v0.sourceValues[xIdx]);
    assertEquals(0, v1.sourceValues[xIdx]);

    // C voice.cpp:783-800 — each block of 16 samples adds diff * 16 / 4 = diff * 4.
    int[] buf = new int[64 * 2];
    int expectedFirstStep = (targetX >> 8) * 16 / 4;
    sound.renderInternal(buf, 16, null);

    // C sound.cpp:2635 — synth-level flag is cleared after the block, while each voice keeps its
    // own expressionSourcesCurrentlySmoothing bit until diff == 0.
    assertEquals(0, sound.expressionSourcesChangedAtSynthLevel);
    assertEquals(1, v0.expressionSourcesCurrentlySmoothing);
    assertEquals(1, v1.expressionSourcesCurrentlySmoothing);
    assertEquals(0, v0.expressionSourcesFinalValueChanged);
    assertEquals(expectedFirstStep, v0.sourceValues[xIdx]);
    assertEquals(expectedFirstStep, v1.sourceValues[xIdx]);

    // Render additional blocks until smoothing converges (diff == 0).
    for (int i = 0; i < 300 && v0.expressionSourcesCurrentlySmoothing != 0; i++) {
      sound.renderInternal(buf, 64, null);
    }
    assertEquals(0, v0.expressionSourcesCurrentlySmoothing);
    assertEquals(0, v1.expressionSourcesCurrentlySmoothing);
    assertEquals(targetX >> 8, v0.sourceValues[xIdx] >> 8);
    assertEquals(targetX >> 8, v1.sourceValues[xIdx] >> 8);
  }

  @Test
  void testPolyphonicExpressionSmoothVsImmediateAndArpMpeUpdate() {
    Sound sound = new Sound();
    sound.triggerNote(60, 100, 2);
    sound.triggerNote(67, 100, 3);

    Voice vCh2 = null;
    Voice vCh3 = null;
    for (Voice v : sound.voices) {
      if (v.inputCharacteristics[1] == 2) vCh2 = v;
      if (v.inputCharacteristics[1] == 3) vCh3 = v;
    }
    assertTrue(vCh2 != null && vCh3 != null);

    int targetY = 0x30000000;
    int targetZ = 0x50000000;
    // Smooth on channel 2 (live MPE CC 74), immediate on channel 3 (step automation).
    sound.polyphonicExpressionEventOnChannelOrNote(targetY, 1, 2, 1, true);
    sound.polyphonicExpressionEventOnChannelOrNote(targetZ, 2, 3, 1, false);

    int yIdx = PatchSource.Y.ordinal();
    int zIdx = PatchSource.AFTERTOUCH.ordinal();

    // Channel 2 smooth: local target set, sourceValues[Y] still 0 before render.
    assertEquals(targetY, vCh2.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(0, vCh2.sourceValues[yIdx]);
    assertEquals(1 << 1, vCh2.expressionSourcesCurrentlySmoothing);

    // Channel 3 immediate: sourceValues[AFTERTOUCH] updated before render.
    assertEquals(targetZ, vCh3.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(targetZ, vCh3.sourceValues[zIdx]);
    assertEquals(0, vCh3.expressionSourcesCurrentlySmoothing);

    // ArpNote MPE values updated in both smooth and immediate paths (sound_instrument.cpp:348-364).
    boolean foundCh2Arp = false;
    boolean foundCh3Arp = false;
    for (int n = 0; n < sound.arpeggiator.notes.size(); n++) {
      Arpeggiator.ArpNote arpNote = sound.arpeggiator.notes.get(n);
      if (arpNote.inputCharacteristics[1] == 2) {
        assertEquals(targetY >> 16, arpNote.mpeValues[1]);
        foundCh2Arp = true;
      } else if (arpNote.inputCharacteristics[1] == 3) {
        assertEquals(targetZ >> 16, arpNote.mpeValues[2]);
        foundCh3Arp = true;
      }
    }
    assertTrue(foundCh2Arp && foundCh3Arp);

    // Render 16 samples and verify channel 2 ramps by (targetY >> 8) * 16 / 4.
    int[] buf = new int[16 * 2];
    sound.renderInternal(buf, 16, null);
    assertEquals((targetY >> 8) * 16 / 4, vCh2.sourceValues[yIdx]);
    assertEquals(targetZ, vCh3.sourceValues[zIdx]);
  }

  @Test
  void testPooledVoiceNoteOnResetsResidualSmoothingFlags() {
    Sound sound = new Sound();
    sound.triggerNote(60, 100, 2);
    Voice pooledVoice = sound.voices.get(0);

    sound.polyphonicExpressionEventOnChannelOrNote(0x40000000, 0, 2, 1, true);
    assertEquals(1, pooledVoice.expressionSourcesCurrentlySmoothing);

    // Mark pooledVoice inactive (simulating voice finish/unassign) and trigger a fresh note that
    // reuses the same Voice instance.
    pooledVoice.active = false;
    sound.triggerNote(62, 100, 2);
    Voice reusedVoice = sound.voices.get(0);
    assertTrue(pooledVoice == reusedVoice);
    // C voice.cpp:333 — noteOn clears expressionSourcesCurrentlySmoothing on pooled voices.
    assertEquals(0, reusedVoice.expressionSourcesCurrentlySmoothing);
    assertEquals(0, reusedVoice.expressionSourcesFinalValueChanged);
    assertEquals(0, reusedVoice.sourceValues[PatchSource.X.ordinal()]);
  }

  @Test
  void testFirmwareSoundMasterChannelPolyAftertouchAndDrumSmoothing() {
    FirmwareSound synth = new FirmwareSound();
    synth.triggerNote(60, 100, 1);
    synth.triggerNote(64, 100, 1);

    // Master channel 0 pitch bend routes to monophonicExpressionEvent and smooths all voices.
    synth.mpePitchBend(0, 12000, true);
    int expectedBend = (12000 - 8192) << 18;
    assertEquals(expectedBend, synth.fw2Sound.monophonicExpressionValues[0]);
    assertEquals(1, synth.fw2Sound.expressionSourcesChangedAtSynthLevel);
    assertEquals(0, synth.fw2Sound.voices.get(0).sourceValues[PatchSource.X.ordinal()]);

    int[] buf = new int[16 * 2];
    synth.fw2Sound.renderInternal(buf, 16, null);
    int expectedStep = (expectedBend >> 8) * 16 / 4;
    assertEquals(expectedStep, synth.fw2Sound.voices.get(0).sourceValues[PatchSource.X.ordinal()]);
    assertEquals(expectedStep, synth.fw2Sound.voices.get(1).sourceValues[PatchSource.X.ordinal()]);

    // Polyphonic aftertouch matches only note 60 (MIDICharacteristic::NOTE = 0).
    synth.mpePolyAftertouch(60, 100, true);
    Voice v60 =
        synth.fw2Sound.voices.get(0).inputCharacteristics[0] == 60
            ? synth.fw2Sound.voices.get(0)
            : synth.fw2Sound.voices.get(1);
    Voice v64 =
        synth.fw2Sound.voices.get(0).inputCharacteristics[0] == 64
            ? synth.fw2Sound.voices.get(0)
            : synth.fw2Sound.voices.get(1);
    assertEquals(100 << 24, v60.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0, v64.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0, v60.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);

    synth.fw2Sound.renderInternal(buf, 16, null);
    assertEquals(((100 << 24) >> 8) * 16 / 4, v60.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    assertEquals(0, v64.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);

    // Drum FirmwareSound routes expression to drumExpressionEvent (all drum voices + active_note).
    FirmwareSound drum = new FirmwareSound();
    drum.isDrum = true;
    drum.triggerNote(60, 100, 1);
    drum.mpePressure(0, 90, true);
    Voice drumVoice = drum.fw2Sound.voices.get(0);
    assertEquals(90 << 24, drumVoice.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals((90 << 24) >> 16, drum.fw2Sound.arpeggiator.active_note.mpeValues[2]);
    assertEquals(0, drumVoice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    drum.fw2Sound.renderInternal(buf, 16, null);
    assertEquals(
        ((90 << 24) >> 8) * 16 / 4, drumVoice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);
    // Silent block early return also resets expressionSourcesChangedAtSynthLevel (sound.cpp:2635).
    Sound silentSound = new Sound();
    silentSound.monophonicExpressionEvent(0x20000000, 1);
    assertEquals(1 << 1, silentSound.expressionSourcesChangedAtSynthLevel);
    silentSound.renderInternal(buf, 16, null);
    assertEquals(0, silentSound.expressionSourcesChangedAtSynthLevel);
  }

  @Test
  void testMidiServiceEndToEndSynthAndKitExpressionRouting() {
    BridgeContract bridge = new BridgeContract();
    PlaybackHandler ph = new PlaybackHandler();
    ProjectModel project = new ProjectModel();

    SynthTrackModel synthTrack = new SynthTrackModel("Synth");
    ClipModel synthClip = new ClipModel("SynthClip", 0, 16);
    FirmwareSound synth = new FirmwareSound();
    synthClip.setSound(synth);
    synthTrack.addClip(synthClip);
    project.getTracks().add(synthTrack);

    KitTrackModel kitTrack = new KitTrackModel("Kit");
    ClipModel kitClip = new ClipModel("KitClip", 1, 16);
    FirmwareKit kit = new FirmwareKit();
    for (int d = 0; d < 4; d++) {
      FirmwareSound drum = new FirmwareSound();
      drum.isDrum = true;
      kit.drumSounds.add(drum);
    }
    kitClip.setSound(kit);
    kitTrack.addClip(kitClip);
    project.getTracks().add(kitTrack);

    ph.setProject(project);
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, ph);

    MidiInputRouter router = new MidiInputRouter(bridge);
    MidiService service = new MidiService(bridge, router);

    // 1. Synth track (track 0): Mod Wheel CC 1 on master channel 0 -> unipolar Y smoothing,
    //    MPE CC 74 on channel 2 -> per-channel Y smoothing, and Poly Aftertouch on note 60 only.
    // Initial clip sync sets all 3 mono expression bits (0b111), matching C++
    // SoundInstrument::setActiveClip (sound_instrument.cpp:279); rendering one block clears them.
    assertEquals(0b111, synth.fw2Sound.expressionSourcesChangedAtSynthLevel);
    service.setActiveTrack(0);
    synth.triggerNote(60, 100, 2);
    synth.triggerNote(64, 100, 3);
    int[] buf = new int[16 * 2];
    synth.fw2Sound.renderInternal(buf, 16, null);
    assertEquals(0, synth.fw2Sound.expressionSourcesChangedAtSynthLevel);

    service.getEngine().midiMessageReceived(MIDIMessage.cc(0, 1, 100), null);
    assertEquals(100 << 24, synth.fw2Sound.monophonicExpressionValues[1]);
    assertEquals(1 << 1, synth.fw2Sound.expressionSourcesChangedAtSynthLevel);

    service.getEngine().midiMessageReceived(MIDIMessage.cc(2, 74, 96), null);
    Voice v60 =
        synth.fw2Sound.voices.get(0).inputCharacteristics[0] == 60
            ? synth.fw2Sound.voices.get(0)
            : synth.fw2Sound.voices.get(1);
    Voice v64 =
        synth.fw2Sound.voices.get(0).inputCharacteristics[0] == 64
            ? synth.fw2Sound.voices.get(0)
            : synth.fw2Sound.voices.get(1);
    assertEquals((96 - 64) << 25, v60.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(0, v64.localExpressionSourceValuesBeforeSmoothing[1]);

    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 60, 110), null);
    assertEquals(110 << 24, v60.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0, v64.localExpressionSourceValuesBeforeSmoothing[2]);

    // Immediate modWheel(val, false) combines mono Y across ALL active voices (ch 2 and ch 3)
    // without clobbering per-voice localExpressionSourceValuesBeforeSmoothing[1].
    synth.modWheel(32, false);
    int perVoiceY60 = (96 - 64) << 25;
    assertEquals(perVoiceY60, v60.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(
        v60.combineExpressionValues(synth.fw2Sound, 1), v60.sourceValues[PatchSource.Y.ordinal()]);
    assertEquals(32 << 24, v64.sourceValues[PatchSource.Y.ordinal()]);

    // 2. Kit track (track 1): every drum triggers internally at kNoteForDrum = 60, while live
    //    polyphonic aftertouch maps noteCode - 36 to the exact drum row (kit.cpp:1725-1737).
    service.setActiveTrack(1);
    kit.drumSounds.get(0).triggerNote(60, 100, 0); // Kick = MIDI 36 -> row 0
    kit.drumSounds.get(2).triggerNote(60, 100, 0); // Snare = MIDI 38 -> row 2

    // Poly aftertouch on Kick (MIDI 36) updates ONLY row 0, leaving Snare (row 2) untouched.
    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 36, 95), null);
    assertEquals(
        95 << 24,
        kit.drumSounds.get(0).fw2Sound.voices.get(0).localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(
        0,
        kit.drumSounds.get(2).fw2Sound.voices.get(0).localExpressionSourceValuesBeforeSmoothing[2]);

    // Poly aftertouch on MIDI note 60 (row 24, out of range for 4-drum kit) must NOT leak to
    // active drums whose internal voice note is kNoteForDrum = 60.
    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 60, 120), null);
    assertEquals(
        0,
        kit.drumSounds.get(2).fw2Sound.voices.get(0).localExpressionSourceValuesBeforeSmoothing[2]);

    // Poly aftertouch on an inactive drum row (MIDI 39 -> row 3) still updates
    // active_note.mpeValues.
    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 39, 80), null);
    assertEquals(
        (80 << 24) >> 16, kit.drumSounds.get(3).fw2Sound.arpeggiator.active_note.mpeValues[2]);

    service.stop();
    bridge.shutdown();
  }

  @Test
  void testConcurrentLiveNoteExpressionAndRenderInternalHasNoArpRace() throws Exception {
    Sound sound = new Sound();
    java.util.concurrent.atomic.AtomicReference<Throwable> error =
        new java.util.concurrent.atomic.AtomicReference<>();
    java.util.concurrent.atomic.AtomicBoolean running =
        new java.util.concurrent.atomic.AtomicBoolean(true);

    Thread audioThread =
        new Thread(
            () -> {
              int[] buf = new int[32 * 2];
              try {
                while (running.get()) {
                  sound.reassessRenderSkippingStatus(32);
                  sound.renderInternal(buf, 32, null);
                }
              } catch (Throwable t) {
                error.compareAndSet(null, t);
              }
            });
    audioThread.start();

    try {
      for (int i = 0; i < 400 && error.get() == null; i++) {
        int note = 48 + (i % 12);
        sound.triggerNote(note, 100, 2);
        sound.polyphonicExpressionEventOnChannelOrNote((i * 1000) << 16, 1, 2, 1, true);
        sound.monophonicExpressionEvent((i * 500) << 16, 0);
        sound.releaseNote(note, 2);
      }
    } finally {
      running.set(false);
      audioThread.join(2000);
    }

    assertEquals(null, error.get(), () -> "Unexpected concurrent race error: " + error.get());
  }
}
