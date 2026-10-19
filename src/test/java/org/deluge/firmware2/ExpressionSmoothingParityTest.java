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
    router.setFollowModeEnabled(false);
    MidiService service = new MidiService(bridge, router);

    // 1. Synth track (track 0): route real MIDIMessage.noteOn on MPE channels 2 & 3 through
    //    MidiService -> MidiInputRouter so voices get real inputCharacteristics[CHANNEL], then test
    //    Mod Wheel CC 1 on master channel 0, MPE CC 74 on channel 2, and Poly Aftertouch on note
    // 60.
    // Initial clip sync sets all 3 mono expression bits (0b111), matching C++
    // SoundInstrument::setActiveClip (sound_instrument.cpp:279); rendering one block clears them.
    assertEquals(0b111, synth.fw2Sound.expressionSourcesChangedAtSynthLevel);
    service.setActiveTrack(0);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(2, 60, 100), null);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(3, 64, 100), null);
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

    // 2. Kit track (track 1): route real MIDIMessage.noteOn on channel 0 for Kick (MIDI 36) and
    //    Snare (MIDI 38); every drum triggers internally at kNoteForDrum = 60, while live
    //    polyphonic aftertouch maps noteCode - 36 to the exact held drum row (kit.cpp:1798-1801).
    service.setActiveTrack(1);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(0, 36, 100), null); // Kick -> row 0
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(0, 38, 100), null); // Snare -> row 2

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

    // Un-auditioned drum row (row 3: lastMidiChannelAuditioned == MIDI_CHANNEL_NONE == 255) ignores
    // poly aftertouch (drum.cpp:40, kit.cpp:1799). Once held on ch 0 (even after its voice decays),
    // poly aftertouch updates active_note.mpeValues[2]!
    assertEquals(FirmwareSound.MIDI_CHANNEL_NONE, kit.drumSounds.get(3).lastMidiChannelAuditioned);
    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 39, 80), null);
    assertEquals(0, kit.drumSounds.get(3).fw2Sound.arpeggiator.active_note.mpeValues[2]);

    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(0, 39, 100), null);
    kit.drumSounds.get(3).fw2Sound.voices.get(0).active =
        false; // voice decayed while pad still held
    service.getEngine().midiMessageReceived(MIDIMessage.polyAftertouch(0, 39, 80), null);
    assertEquals(
        (80 << 24) >> 16, kit.drumSounds.get(3).fw2Sound.arpeggiator.active_note.mpeValues[2]);

    // CC 1 (mod wheel) on Kit track is ignored (kit.cpp:1681-1682, 1770).
    service.getEngine().midiMessageReceived(MIDIMessage.cc(0, 1, 100), null);
    assertEquals(
        0,
        kit.drumSounds.get(0).fw2Sound.voices.get(0).localExpressionSourceValuesBeforeSmoothing[1]);

    // Sequenced ClipModel drum hit after live polyAftertouch(0, 36, 95) starts clean with 0
    // aftertouch instead of inheriting sticky live drum inputs (note_row.cpp:4342-4347)!
    service.getEngine().midiMessageReceived(MIDIMessage.noteOff(0, 36, 0), null);
    kit.drumSounds.get(0).fw2Sound.voices.clear();
    kitClip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 1.0f, 1.0f, 0));
    kitClip.setPos(0);
    kitClip.processCurrentPos(0);
    assertEquals(
        0,
        kit.drumSounds.get(0).fw2Sound.voices.get(0).localExpressionSourceValuesBeforeSmoothing[2]);

    // Holding Kick live on MPE channel 5 while a sequenced step releases via kit.releaseDrum(0)
    // must NOT clear lastMidiChannelAuditioned mid-hold (note_row.cpp:2623-2630 vs kit.cpp:1970)!
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(5, 36, 100), null);
    assertEquals(5, kit.drumSounds.get(0).lastMidiChannelAuditioned);
    kit.releaseDrum(0);
    assertEquals(5, kit.drumSounds.get(0).lastMidiChannelAuditioned);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOff(5, 36, 0), null);
    assertEquals(FirmwareSound.MIDI_CHANNEL_NONE, kit.drumSounds.get(0).lastMidiChannelAuditioned);

    // Pre-NoteOn MPE initial strike expression on channel 4 seeds Synth voice at NoteOn
    // (playback_handler.cpp:3176-3297, melodic_instrument.cpp:106)!
    service.setActiveTrack(0);
    service.getEngine().midiMessageReceived(MIDIMessage.pitchBend(4, 0, 96), null);
    service.getEngine().midiMessageReceived(MIDIMessage.cc(4, 74, 100), null);
    service.getEngine().midiMessageReceived(MIDIMessage.channelAftertouch(4, 88), null);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(4, 67, 100), null);
    Voice v67 = synth.fw2Sound.voices.get(synth.fw2Sound.voices.size() - 1);
    assertEquals(
        (((96 << 7) - 8192) << 18) & 0xFFFF0000, v67.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals((100 - 64) << 25, v67.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(88 << 24, v67.localExpressionSourceValuesBeforeSmoothing[2]);

    service.stop();
    bridge.shutdown();
  }

  @Test
  void testKitPitchBendRangeMpeChannelIsolationAndNoteRowDispatch() {
    BridgeContract bridge = new BridgeContract();
    PlaybackHandler ph = new PlaybackHandler();
    ProjectModel project = new ProjectModel();
    KitTrackModel kitTrack = new KitTrackModel("Kit");
    ClipModel clip = new ClipModel("KIT", 2, 16);
    FirmwareKit kit = new FirmwareKit();
    // Configure Kick (row 0) and Snare (row 1) with audible wave oscillators so we can measure real
    // rendered pitch shifts in audio!
    for (int r = 0; r < 2; r++) {
      var d = kit.drumSounds.get(r);
      d.oscTypes[0] = Oscillator.OscType.SAW;
      d.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = 0x40000000;
      d.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
      d.paramNeutralValues[Param.LOCAL_NOISE_VOLUME] = Integer.MIN_VALUE;
    }
    clip.setSound(kit);
    kitTrack.addClip(clip);
    project.getTracks().add(kitTrack);
    ph.setProject(project);
    bridge.setGlobalObject(BridgeContract.G_PLAYBACK_HANDLER, ph);

    MidiInputRouter kitRouter = new MidiInputRouter(bridge);
    kitRouter.setFollowModeEnabled(false);
    MidiService service = new MidiService(bridge, kitRouter);
    service.setActiveTrack(0);

    // Trigger Kick (MIDI 36) on MPE member channel 2 and Snare (MIDI 37) on MPE member channel 3
    // via real MIDIMessage.noteOn routed through MidiService -> MidiInputRouter!
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(2, 36, 100), null);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(3, 37, 100), null);

    // 1. Default drum bendRangeFingerLevel equals bendRangeMain = 2 semitones (param_set.cpp:582),
    //    NOT 48 semitones!
    assertEquals(2, kit.drumSounds.get(0).fw2Sound.bendRangeFingerLevel);
    assertEquals(2, kit.drumSounds.get(1).fw2Sound.bendRangeFingerLevel);

    // 2. Per-pad MPE member-channel isolation (kit.cpp:1640, 1684, 1813):
    //    Sending CC 74, Pitch Bend, and Channel Aftertouch on channel 2 affects ONLY Kick (row 0)
    //    and leaves Snare (row 1, channel 3) completely untouched!
    service.getEngine().midiMessageReceived(MIDIMessage.cc(2, 74, 112), null);
    service.getEngine().midiMessageReceived(MIDIMessage.pitchBend(2, 127, 127), null);
    service.getEngine().midiMessageReceived(MIDIMessage.channelAftertouch(2, 105), null);

    Voice kickVoice = kit.drumSounds.get(0).fw2Sound.voices.get(0);
    Voice snareVoice = kit.drumSounds.get(1).fw2Sound.voices.get(0);
    assertEquals(
        ((16383 - 8192) << 18) & 0xFFFF0000,
        kickVoice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals((112 - 64) << 25, kickVoice.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(105 << 24, kickVoice.localExpressionSourceValuesBeforeSmoothing[2]);
    assertEquals(0, snareVoice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(0, snareVoice.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(0, snareVoice.localExpressionSourceValuesBeforeSmoothing[2]);

    // Verify rendered pitch shift on Kick is ~+2 semitones (2^(2/12) ≈ 1.12246x), NOT +48
    // semitones, checking BOTH overallPitchAdjust AND actual rendered 128-sample oscPos advance!
    int kickPhaseBefore = kickVoice.unisonParts[0].sources[0].oscPos;
    int snarePhaseBefore = snareVoice.unisonParts[0].sources[0].oscPos;
    int[] kickBuf = new int[128 * 2];
    int[] snareBuf = new int[128 * 2];
    kit.drumSounds.get(0).fw2Sound.renderInternal(kickBuf, 128, null);
    kit.drumSounds.get(1).fw2Sound.renderInternal(snareBuf, 128, null);
    long kickPhaseAdvance =
        Integer.toUnsignedLong(kickVoice.unisonParts[0].sources[0].oscPos - kickPhaseBefore);
    long snarePhaseAdvance =
        Integer.toUnsignedLong(snareVoice.unisonParts[0].sources[0].oscPos - snarePhaseBefore);
    double ratio = (double) kickPhaseAdvance / (double) snarePhaseAdvance;
    assertEquals(Math.pow(2.0, 2.0 / 12.0), ratio, 0.002);

    // 3. Master channel (channel 0) combines with active MPE finger input and saturates cleanly
    //    (drum.cpp:103-106).
    service.getEngine().midiMessageReceived(MIDIMessage.pitchBend(0, 127, 127), null);
    assertEquals(0x7FFF0000, kickVoice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(
        ((16383 - 8192) << 18) & 0xFFFF0000,
        snareVoice.localExpressionSourceValuesBeforeSmoothing[0]);

    // 3b. Releasing Kick on channel 2 unlatches lastMidiChannelAuditioned (kit.cpp:1970) so reusing
    //     MPE channel 2 on Snare never cross-modulates Kick's decaying tail, AND re-striking Kick
    //     on clean MPE channel 3 starts unbent instead of inheriting ch 2's stale finger bend
    //     (kit.cpp:1505-1508)!
    service
        .getEngine()
        .midiMessageReceived(MIDIMessage.pitchBend(0, 0, 64), null); // reset master X
    service.getEngine().midiMessageReceived(MIDIMessage.noteOff(2, 36, 0), null);
    assertEquals(FirmwareSound.MIDI_CHANNEL_NONE, kit.drumSounds.get(0).lastMidiChannelAuditioned);
    int kickBendAfterRelease = kickVoice.localExpressionSourceValuesBeforeSmoothing[0];
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(2, 37, 100), null);
    service.getEngine().midiMessageReceived(MIDIMessage.cc(2, 74, 120), null);
    assertEquals(kickBendAfterRelease, kickVoice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals((120 - 64) << 25, snareVoice.localExpressionSourceValuesBeforeSmoothing[1]);

    // Re-strike Kick on clean MPE channel 3 -> new voice starts completely unbent (0, 0, 0), while
    // pre-NoteOn MPE strike values on channel 5 seed Kick at NoteOn!
    kit.drumSounds.get(0).fw2Sound.voices.clear();
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(3, 36, 100), null);
    Voice cleanRehitKick = kit.drumSounds.get(0).fw2Sound.voices.get(0);
    assertEquals(0, cleanRehitKick.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(0, cleanRehitKick.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(0, cleanRehitKick.localExpressionSourceValuesBeforeSmoothing[2]);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOff(3, 36, 0), null);

    kit.drumSounds.get(0).fw2Sound.voices.clear();
    service.getEngine().midiMessageReceived(MIDIMessage.pitchBend(5, 0, 96), null);
    service.getEngine().midiMessageReceived(MIDIMessage.cc(5, 74, 104), null);
    service.getEngine().midiMessageReceived(MIDIMessage.channelAftertouch(5, 72), null);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(5, 36, 100), null);
    kickVoice = kit.drumSounds.get(0).fw2Sound.voices.get(0);
    assertEquals(
        (((96 << 7) - 8192) << 18) & 0xFF000000,
        kickVoice.localExpressionSourceValuesBeforeSmoothing[0]);
    assertEquals(
        ((104 - 64) << 25) & 0xFF000000, kickVoice.localExpressionSourceValuesBeforeSmoothing[1]);
    assertEquals(72 << 24, kickVoice.localExpressionSourceValuesBeforeSmoothing[2]);

    // 4. Explicit per-row bendRange (including explicit 48!) and default (-1 -> 2) via
    // NoteRowModel, ClipModel.setSound (before any sequencer step runs), and triggerNote.
    org.deluge.model.NoteRowModel row0 = clip.getOrCreateRow(0);
    row0.setPitch(0); // default bendRange == -1 -> must resolve to 2
    row0.prepareExpressionDispatch(kit);
    row0.setExpressionParam(
        org.deluge.modulation.params.ExpressionParamSet.X_PITCH_BEND,
        0,
        java.util.List.of(new org.deluge.modulation.automation.ParamNode(0, 0x40000000, false)));
    row0.getParamManager().setPlayPos(0, 96, false);
    row0.getParamManager().processCurrentPos(0, 0, 96, false, false, true);
    assertEquals(2, kit.drumSounds.get(0).fw2Sound.bendRangeFingerLevel);
    assertEquals(0x40000000, kickVoice.localExpressionSourceValuesBeforeSmoothing[0]);

    org.deluge.model.NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setPitch(1);
    row1.setBendRange(48); // explicit 48 on row 1 must apply immediately on setSound + live MIDI!
    FirmwareKit freshKit = new FirmwareKit();
    clip.setSound(freshKit);
    assertEquals(48, freshKit.drumSounds.get(1).fw2Sound.bendRangeFingerLevel);
    service.getEngine().midiMessageReceived(MIDIMessage.noteOn(3, 37, 100), null);
    assertEquals(48, freshKit.drumSounds.get(1).fw2Sound.bendRangeFingerLevel);
    clip.setSound(kit);
    row1.prepareExpressionDispatch(kit);
    row1.setExpressionParam(
        org.deluge.modulation.params.ExpressionParamSet.X_PITCH_BEND,
        0,
        java.util.List.of(new org.deluge.modulation.automation.ParamNode(0, 0x20000000, false)));
    row1.getParamManager().setPlayPos(0, 96, false);
    row1.getParamManager().processCurrentPos(0, 0, 96, false, false, true);
    kit.drumSounds.get(1).triggerNote(60, 100);
    assertEquals(48, kit.drumSounds.get(1).fw2Sound.bendRangeFingerLevel);

    service.stop();
    bridge.shutdown();
  }

  @Test
  void testNegativeBoundariesSaturationBlockSizesAndModWheelStaleFlagClear() {
    Sound sound = new Sound();
    sound.oscTypes[0] = Oscillator.OscType.SAW;
    sound.patchedParamValues[Param.LOCAL_OSC_A_VOLUME] = 0x40000000;
    sound.triggerNote(60, 100, 2);
    Voice v = sound.voices.get(0);

    // 1. Saturation clamping at both Integer.MIN_VALUE and Integer.MAX_VALUE:
    sound.monophonicExpressionEvent(Integer.MIN_VALUE, 0);
    sound.polyphonicExpressionEventOnChannelOrNote(Integer.MIN_VALUE, 0, 2, 1, true);
    assertEquals(Integer.MIN_VALUE, v.combineExpressionValues(sound, 0));

    // C voice.cpp:351 — lshiftAndSaturate<1>((mono >> 1) + (local >> 1)) saturates positive max to
    // 0x7FFFFFFE (bit 0 shifted left by 1) and negative min to Integer.MIN_VALUE (0x80000000).
    sound.monophonicExpressionEvent(Integer.MAX_VALUE, 0);
    sound.polyphonicExpressionEventOnChannelOrNote(Integer.MAX_VALUE, 0, 2, 1, true);
    assertEquals(0x7FFFFFFE, v.combineExpressionValues(sound, 0));

    // 2. Full negative-to-positive and positive-to-negative bipolar swings across block sizes
    //    128 (hardware live block), 256, 768 (standalone >512 block), and 1024 (full snap):
    sound.monophonicExpressionValues[0] = 0;
    v.localExpressionSourceValuesBeforeSmoothing[0] = Integer.MAX_VALUE;
    v.sourceValues[PatchSource.X.ordinal()] = Integer.MIN_VALUE;
    v.expressionSourcesCurrentlySmoothing = 1;

    int[] buf128 = new int[128 * 2];
    sound.renderInternal(buf128, 128, null);
    int after128 = v.sourceValues[PatchSource.X.ordinal()];
    assertTrue(after128 > Integer.MIN_VALUE && after128 < 0, "128-sample step must advance 1/8th");

    // Test 256-sample standalone block across full min->max swing without int32 sign-flip overflow!
    v.sourceValues[PatchSource.X.ordinal()] = Integer.MIN_VALUE;
    v.expressionSourcesCurrentlySmoothing = 1;
    int[] buf256 = new int[256 * 2];
    sound.renderInternal(buf256, 256, null);
    int after256 = v.sourceValues[PatchSource.X.ordinal()];
    assertEquals(-1_073_741_888, after256, "256-sample step must advance 1/4th without overflow");

    // Test 768-sample standalone block across full max->min swing without int32 sign-flip overflow!
    v.localExpressionSourceValuesBeforeSmoothing[0] = Integer.MIN_VALUE;
    v.sourceValues[PatchSource.X.ordinal()] = Integer.MAX_VALUE;
    v.expressionSourcesCurrentlySmoothing = 1;
    int[] buf768 = new int[768 * 2];
    sound.renderInternal(buf768, 768, null);
    int after768 = v.sourceValues[PatchSource.X.ordinal()];
    assertTrue(after768 < 0 && after768 > Integer.MIN_VALUE, "768-sample step must advance 3/4th");

    // Test 1024-sample standalone block snaps cleanly to target and clears smoothing bit!
    int[] buf1024 = new int[1024 * 2];
    sound.renderInternal(buf1024, 1024, null);
    assertEquals(Integer.MIN_VALUE, v.sourceValues[PatchSource.X.ordinal()]);
    assertEquals(0, v.expressionSourcesCurrentlySmoothing & 1);

    // 3. Immediate FirmwareSound.modWheel(val, false) must also clear pending synth-level smooth
    //    Y flag (expressionSourcesChangedAtSynthLevel bit 1) so next Voice.render stays immediate!
    org.deluge.engine.FirmwareSound fs = new org.deluge.engine.FirmwareSound();
    fs.triggerNote(60, 100, 2);
    Voice fv = fs.fw2Sound.voices.get(0);
    fs.modWheel(127, true); // sets expressionSourcesChangedAtSynthLevel |= (1 << 1)
    assertEquals(1 << 1, fs.fw2Sound.expressionSourcesChangedAtSynthLevel & (1 << 1));
    fs.modWheel(64, false); // immediate override must clear bit 1 on both synth and voice!
    assertEquals(0, fs.fw2Sound.expressionSourcesChangedAtSynthLevel & (1 << 1));
    assertEquals(0, fv.expressionSourcesCurrentlySmoothing & (1 << 1));
    assertEquals(64 << 24, fv.sourceValues[PatchSource.Y.ordinal()]);
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
