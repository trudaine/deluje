package org.deluge.modulation.params;

import static org.junit.jupiter.api.Assertions.*;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareMidiInstrument;
import org.deluge.engine.FirmwareSound;
import org.deluge.midi.MidiEngine;
import org.deluge.midi.MidiTransport;
import org.deluge.model.ClipModel;
import org.deluge.model.KitTrackModel;
import org.deluge.model.NoteRowModel;
import org.deluge.model.PatternModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.modulation.automation.ParamNode;
import org.deluge.modulation.patch.PatchSource;
import org.deluge.project.ProjectSerializer;
import org.deluge.shadow.midi.MidiMsg;
import org.deluge.xml.SongXmlParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

/**
 * Verifies C++ parity for {@link ExpressionParamSet} ({@code param_set.h:131-158}, {@code
 * param_set.cpp:574-730}, {@code midi_instrument.h:136-147}, {@code midi_instrument.cpp:286-322}),
 * {@code <instrumentClip>} / {@code <noteRow>} {@code <expressionData>} XML parsing and
 * serialization ({@code instrument_clip.cpp:2427-2435, 2857-2886}, {@code note_row.cpp:3539-3654}),
 * and clip/pattern copy lifecycles.
 */
class ExpressionParamSetParityTest {

  @Test
  void valueChangedEnoughToMatterAndMidiConversionsMatchCpp() {
    // C mod_controllable.h:59 — non-MIDI mode triggers on any Q31 value change.
    assertTrue(
        ExpressionParamSet.valueChangedEnoughToMatter(
            ExpressionParamSet.X_PITCH_BEND, 100, 101, false));

    // C midi_instrument.h:140-144 — MIDI mode: 14-bit pitch bend (>> 18) and 7-bit Y/Z (>> 24).
    assertFalse(
        ExpressionParamSet.valueChangedEnoughToMatter(
            ExpressionParamSet.X_PITCH_BEND, 0, (1 << 18) - 1, true));
    assertTrue(
        ExpressionParamSet.valueChangedEnoughToMatter(
            ExpressionParamSet.X_PITCH_BEND, 0, 1 << 18, true));

    assertFalse(
        ExpressionParamSet.valueChangedEnoughToMatter(
            ExpressionParamSet.Y_SLIDE_TIMBRE, 0, (1 << 24) - 1, true));
    assertTrue(
        ExpressionParamSet.valueChangedEnoughToMatter(
            ExpressionParamSet.Y_SLIDE_TIMBRE, 0, 1 << 24, true));

    // C midi_instrument.cpp:294, 305, 314
    assertEquals(8192, ExpressionParamSet.toMidiPitchBend14(0));
    assertEquals(16383, ExpressionParamSet.toMidiPitchBend14(Integer.MAX_VALUE));
    assertEquals(0, ExpressionParamSet.toMidiPitchBend14(Integer.MIN_VALUE));

    assertEquals(0, ExpressionParamSet.toMidiModWheel7(0));
    assertEquals(64, ExpressionParamSet.toMidiModWheel7(64 << 24));
    assertEquals(127, ExpressionParamSet.toMidiModWheel7(Integer.MAX_VALUE));

    assertEquals(0, ExpressionParamSet.toMidiChannelPressure7(0));
    assertEquals(100, ExpressionParamSet.toMidiChannelPressure7(100 << 24));
    assertEquals(127, ExpressionParamSet.toMidiChannelPressure7(Integer.MAX_VALUE));
  }

  @Test
  void timelineSteppingAndSampleTickInterpolationMatchCpp() {
    ParamManager pm = new ParamManager();
    ExpressionParamSet expr = pm.getExpressionParamSet();
    List<int[]> events = new ArrayList<>();
    pm.setExpressionListener((dim, val) -> events.add(new int[] {dim, val}));

    expr.setParam(
        ExpressionParamSet.X_PITCH_BEND,
        0,
        List.of(new ParamNode(0, 0, false), new ParamNode(24, 240_000_000, true)));

    // At tick 0, slope ramps toward 240_000_000 at tick 24.
    pm.setPlayPos(0, 96, false);
    pm.processCurrentPos(0, 0, 96, false, false, true);
    assertTrue(expr.isAnyParamInterpolating());
    assertEquals(24, pm.ticksTilNextEvent);

    // Sample-domain tickSamples advances currentValue and notifies listener.
    events.clear();
    int tickInverse = (int) (2147483648.0 / 459.375); // ~459.375 samples/tick at 120 BPM
    pm.tickSamples(128, tickInverse);
    assertFalse(events.isEmpty());
    assertEquals(ExpressionParamSet.X_PITCH_BEND, events.get(0)[0]);
    assertTrue(events.get(0)[1] > 0);

    // Ping-pong bounce flips the interpolation slope sign (C param_set.cpp:371-379).
    int slopeBefore = expr.getParam(ExpressionParamSet.X_PITCH_BEND).valueIncrementPerHalfTick;
    assertTrue(slopeBefore > 0);
    pm.notifyPingpongOccurred();
    assertEquals(
        -slopeBefore, expr.getParam(ExpressionParamSet.X_PITCH_BEND).valueIncrementPerHalfTick);
  }

  @Test
  void clipAndRowExpressionDispatchToSynthAndMidiInstruments() {
    ClipModel clip = new ClipModel("EXPR_CLIP", 4, 4);
    clip.setLoopLength(96);
    FirmwareSound synth = new FirmwareSound();
    clip.setSound(synth);

    // Clip-level monophonic Y expression at pos 0 and pos 12.
    clip.setExpressionParam(
        ExpressionParamSet.Y_SLIDE_TIMBRE,
        0,
        List.of(new ParamNode(0, 0x20000000, false), new ParamNode(12, 0x40000000, false)));

    // Row 0 (pitch 60) has MPE Z pressure at pos 0 and pos 6.
    NoteRowModel row0 = clip.getOrCreateRow(0);
    row0.setPitch(60);
    row0.setExpressionParam(
        ExpressionParamSet.Z_PRESSURE,
        0,
        List.of(new ParamNode(0, 0x10000000, false), new ParamNode(6, 0x30000000, false)));
    clip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 1.0f, 1.0f, 60));

    clip.setPos(0);
    clip.processCurrentPos(0);

    // Clip-level monophonic expression updated synth.fw2Sound.monophonicExpressionValues[Y].
    assertEquals(0x20000000, synth.fw2Sound.monophonicExpressionValues[1]);
    assertEquals(0x20000000, synth.monophonicExpressionValues[1]);

    // Triggered voice on row 0 received initial MPE Z value (0x10000000 >> 16 << 16 == 0x10000000).
    assertFalse(synth.fw2Sound.voices.isEmpty());
    var voice = synth.fw2Sound.voices.get(0);
    assertEquals(0x10000000, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);

    // Advance to tick 6: row 0 MPE Z updates the active voice on pitch 60 to 0x30000000.
    for (int t = 1; t <= 6; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
    assertEquals(0x30000000, voice.sourceValues[PatchSource.AFTERTOUCH.ordinal()]);

    // MIDI clip dispatches 14-bit pitch bend and 7-bit CC1 / Channel Aftertouch via MidiEngine.
    MidiEngine prevEngine = MidiEngine.instance;
    MidiEngine testEngine = new MidiEngine();
    List<MidiMsg> sent = new ArrayList<>();
    testEngine.addOutputTransport(
        new MidiTransport() {
          @Override
          public String getPortName() {
            return "TestOut";
          }

          @Override
          public boolean isOutput() {
            return true;
          }

          @Override
          public boolean isInput() {
            return false;
          }

          @Override
          public boolean sendMessage(MidiMsg message) {
            sent.add(message);
            return true;
          }

          @Override
          public void close() {}
        });
    MidiEngine.instance = testEngine;
    try {
      ClipModel midiClip = new ClipModel("MIDI_EXPR", 4, 4);
      midiClip.setLoopLength(96);
      FirmwareMidiInstrument midiInst = new FirmwareMidiInstrument(2, false);
      midiClip.setSound(midiInst);
      midiClip.setExpressionParam(
          ExpressionParamSet.X_PITCH_BEND,
          0,
          List.of(new ParamNode(0, 0, false), new ParamNode(10, 2048 << 18, false)));
      midiClip.setExpressionParam(
          ExpressionParamSet.Z_PRESSURE,
          0,
          List.of(new ParamNode(0, 0, false), new ParamNode(10, 90 << 24, false)));

      midiClip.setPos(0);
      midiClip.processCurrentPos(0);
      sent.clear();
      for (int t = 1; t <= 10; t++) {
        midiClip.incrementPos(1);
        midiClip.processCurrentPos(1);
      }
      // Pitch bend on ch 2 (index 1, status 0xE1) with 14-bit value 8192 + 2048 = 10240 (LSB 0, MSB
      // 80)
      assertTrue(
          sent.stream()
              .anyMatch(
                  m ->
                      (m.data1 & 0xFF) == 0xE1 && (m.data2 & 0x7F) == 0 && (m.data3 & 0x7F) == 80));
      // Channel aftertouch on ch 2 (status 0xD1) with value 90
      assertTrue(sent.stream().anyMatch(m -> (m.data1 & 0xFF) == 0xD1 && (m.data2 & 0x7F) == 90));
    } finally {
      MidiEngine.instance = prevEngine;
    }
  }

  @Test
  void xmlRoundTripPreservesClipAndNoteRowExpressionData(@TempDir Path tempDir) throws Exception {
    String xml =
        """
        <?xml version="1.0" encoding="UTF-8"?>
        <song
          firmwareVersion="4.1.4-c1.2.0"
          earliestCompatibleFirmware="4.1.0"
          inputTickMagnitude="1">
          <instruments>
            <sound name="Lead" presetSlot="0" presetSubSlot="-1"/>
            <kit name="Beat" presetSlot="0" presetSubSlot="-1">
              <soundSources>
                <sound name="Kick"/>
              </soundSources>
            </kit>
          </instruments>
          <sessionClips>
            <instrumentClip
              clipName="SynthExpr"
              instrumentPresetName="Lead"
              instrumentPresetFolder="SYNTHS"
              length="96"
              isPlaying="1"
              channelPressure="0x11000000">
              <soundParams/>
              <noteRows>
                <noteRow
                  y="60"
                  length="48"
                  noteDataWithSplitProb="0x0000000000000018404014000000">
                  <expressionData
                    pitchBend="0x00000000000000000000000020000000800000183000000000000048"
                    channelPressure="0x05000000"/>
                </noteRow>
              </noteRows>
              <expressionData
                pitchBend="0x1000000010000000000000004000000080000030"
                yExpression="0x22000000"/>
              <bendRange>12</bendRange>
              <bendRangeMPE>24</bendRangeMPE>
            </instrumentClip>
            <instrumentClip
              clipName="KitExpr"
              instrumentPresetName="Beat"
              instrumentPresetFolder="KITS"
              length="96"
              isPlaying="1">
              <kitParams/>
              <noteRows>
                <noteRow
                  drumIndex="0"
                  bendRange="7"
                  noteDataWithSplitProb="0x0000000000000018404014000000">
                  <expressionData pressure="0x33000000"/>
                </noteRow>
              </noteRows>
            </instrumentClip>
          </sessionClips>
          <arrangementOnlyClips>
            <instrumentClip
              clipName="ArrSynthExpr"
              instrumentPresetName="Lead"
              instrumentPresetFolder="SYNTHS"
              length="96">
              <soundParams/>
              <noteRows>
                <noteRow
                  y="64"
                  noteDataWithSplitProb="0x0000000000000018404014000000">
                  <expressionData yExpression="0x44000000"/>
                </noteRow>
              </noteRows>
              <expressionData pitchBend="0x18000000"/>
            </instrumentClip>
          </arrangementOnlyClips>
        </song>
        """;

    ProjectModel project =
        SongXmlParser.parseSong(
            new ByteArrayInputStream(xml.getBytes(StandardCharsets.UTF_8)), "ExprTest");
    SynthTrackModel synthTrack =
        project.getTracks().stream()
            .filter(SynthTrackModel.class::isInstance)
            .map(SynthTrackModel.class::cast)
            .findFirst()
            .orElseThrow();
    KitTrackModel kitTrack =
        project.getTracks().stream()
            .filter(KitTrackModel.class::isInstance)
            .map(KitTrackModel.class::cast)
            .findFirst()
            .orElseThrow();

    ClipModel synthClip =
        synthTrack.getClips().stream()
            .filter(c -> !c.isArrangementOnly())
            .findFirst()
            .orElseThrow();
    assertEquals(12, synthClip.getBendRange());
    assertEquals(24, synthClip.getBendRangeMPE());
    // Clip-level pitchBend, yExpression, and legacy channelPressure attribute on <instrumentClip>.
    ExpressionParamSet clipExpr = synthClip.getExpressionParams();
    assertEquals(0x10000000, clipExpr.getParam(ExpressionParamSet.X_PITCH_BEND).currentValue);
    assertEquals(2, clipExpr.getParam(ExpressionParamSet.X_PITCH_BEND).nodes.size());
    assertTrue(clipExpr.getParam(ExpressionParamSet.X_PITCH_BEND).nodes.get(1).interpolated);
    assertEquals(0x22000000, clipExpr.getParam(ExpressionParamSet.Y_SLIDE_TIMBRE).currentValue);
    assertEquals(0x11000000, clipExpr.getParam(ExpressionParamSet.Z_PRESSURE).currentValue);

    // NoteRow 0 has independent length 48, so node at pos 0x48 (72) is dropped by rowReadUpTo=48!
    NoteRowModel synthRow0 = synthClip.getNoteRowsMap().get(0);
    assertNotNull(synthRow0);
    assertEquals(2, synthRow0.getExpressionParams().getParam(0).nodes.size());
    assertEquals(24, synthRow0.getExpressionParams().getParam(0).nodes.get(1).pos);
    assertEquals(0x05000000, synthRow0.getExpressionParams().getParam(2).currentValue);

    // Kit row 0 has bendRange=7 and pressure=0x33000000.
    ClipModel kitClip = kitTrack.getClips().get(0);
    NoteRowModel kitRow0 = kitClip.getNoteRowsMap().get(0);
    assertEquals(7, kitRow0.getBendRange());
    assertEquals(0x33000000, kitRow0.getExpressionParams().getParam(2).currentValue);

    // Arrangement-only clip and row expression data.
    ClipModel arrClip =
        synthTrack.getClips().stream()
            .filter(ClipModel::isArrangementOnly)
            .findFirst()
            .orElseThrow();
    assertEquals(0x18000000, arrClip.getExpressionParams().getParam(0).currentValue);
    assertEquals(
        0x44000000, arrClip.getNoteRowsMap().get(0).getExpressionParams().getParam(1).currentValue);

    // Round-trip via ProjectSerializer and re-parse.
    Path saved = tempDir.resolve("roundtrip_expr.xml");
    ProjectSerializer.save(project, saved.toFile());
    ProjectModel reparsed = SongXmlParser.parseSong(saved.toFile());

    SynthTrackModel reparsedSynth =
        reparsed.getTracks().stream()
            .filter(SynthTrackModel.class::isInstance)
            .map(SynthTrackModel.class::cast)
            .findFirst()
            .orElseThrow();
    ClipModel reparsedSynthClip =
        reparsedSynth.getClips().stream()
            .filter(c -> !c.isArrangementOnly())
            .findFirst()
            .orElseThrow();
    assertEquals(12, reparsedSynthClip.getBendRange());
    assertEquals(24, reparsedSynthClip.getBendRangeMPE());
    assertEquals(0x10000000, reparsedSynthClip.getExpressionParams().getParam(0).currentValue);
    assertEquals(2, reparsedSynthClip.getExpressionParams().getParam(0).nodes.size());
    assertEquals(0x22000000, reparsedSynthClip.getExpressionParams().getParam(1).currentValue);
    assertEquals(0x11000000, reparsedSynthClip.getExpressionParams().getParam(2).currentValue);

    NoteRowModel reparsedSynthRow0 = reparsedSynthClip.getNoteRowsMap().get(0);
    assertEquals(2, reparsedSynthRow0.getExpressionParams().getParam(0).nodes.size());
    assertEquals(0x05000000, reparsedSynthRow0.getExpressionParams().getParam(2).currentValue);

    KitTrackModel reparsedKit =
        reparsed.getTracks().stream()
            .filter(KitTrackModel.class::isInstance)
            .map(KitTrackModel.class::cast)
            .findFirst()
            .orElseThrow();
    NoteRowModel reparsedKitRow0 = reparsedKit.getClips().get(0).getNoteRowsMap().get(0);
    assertEquals(7, reparsedKitRow0.getBendRange());
    assertEquals(0x33000000, reparsedKitRow0.getExpressionParams().getParam(2).currentValue);
  }

  @Test
  void deepCopyRestoreFromAndPatternSnapshotPreserveExpressionData() {
    ClipModel clip = new ClipModel("SRC", 2, 4);
    clip.setBendRange(12);
    clip.setBendRangeMPE(36);
    clip.setExpressionParam(
        ExpressionParamSet.X_PITCH_BEND, 0x12340000, List.of(new ParamNode(12, 0x20000000, true)));
    NoteRowModel row1 = clip.getOrCreateRow(1);
    row1.setBendRange(9);
    row1.setExpressionParam(
        ExpressionParamSet.Z_PRESSURE, 0x08000000, List.of(new ParamNode(6, 0x10000000, false)));

    ClipModel copy = clip.deepCopy("COPY");
    assertEquals(12, copy.getBendRange());
    assertEquals(36, copy.getBendRangeMPE());
    assertEquals(0x12340000, copy.getExpressionParams().getParam(0).currentValue);
    assertEquals(1, copy.getExpressionParams().getParam(0).nodes.size());
    assertEquals(9, copy.getNoteRowsMap().get(1).getBendRange());
    assertEquals(
        0x08000000, copy.getNoteRowsMap().get(1).getExpressionParams().getParam(2).currentValue);

    // Mutate clip and restore from copy.
    clip.setBendRange(-1);
    clip.setBendRangeMPE(-1);
    clip.clearExpressionParams();
    row1.setBendRange(-1);
    row1.clearExpressionParams();
    clip.restoreFrom(copy);
    assertEquals(12, clip.getBendRange());
    assertEquals(36, clip.getBendRangeMPE());
    assertEquals(0x12340000, clip.getExpressionParams().getParam(0).currentValue);
    assertEquals(9, clip.getNoteRowsMap().get(1).getBendRange());
    assertEquals(
        0x08000000, clip.getNoteRowsMap().get(1).getExpressionParams().getParam(2).currentValue);

    // Restoring from a snapshot with empty noteRows clears added row expression/bendRange.
    ClipModel emptySnap = new ClipModel("EMPTY", 2, 4);
    clip.restoreFrom(emptySnap);
    assertEquals(-1, clip.getNoteRowsMap().get(1).getBendRange());
    assertFalse(clip.getNoteRowsMap().get(1).getExpressionParams().containsSomething());

    // PatternModel.ClipSnapshot round-trip.
    PatternModel.ClipSnapshot snap = PatternModel.ClipSnapshot.fromClipModel(copy, 0, "Lead");
    ClipModel target = new ClipModel("DST", 2, 4);
    snap.applyTo(target);
    assertEquals(12, target.getBendRange());
    assertEquals(36, target.getBendRangeMPE());
    assertEquals(0x12340000, target.getExpressionParams().getParam(0).currentValue);
    assertEquals(9, target.getNoteRowsMap().get(1).getBendRange());
    assertEquals(
        0x08000000, target.getNoteRowsMap().get(1).getExpressionParams().getParam(2).currentValue);
  }

  @Test
  void multiClipSynthTrackAndKitDrumInitialMpeAndArpUpdateMatchCpp() {
    // 1. Multi-clip SynthTrackModel: attaching clip1 must not clobber clip0's expression on sound.
    FirmwareSound synth = new FirmwareSound();
    ClipModel clip0 = new ClipModel("CLIP0", 2, 4);
    clip0.setLoopLength(96);
    clip0.setExpressionParam(
        ExpressionParamSet.Y_SLIDE_TIMBRE,
        0x20000000,
        List.of(new ParamNode(0, 0x20000000, false)));
    ClipModel clip1 = new ClipModel("CLIP1", 2, 4);
    clip1.setLoopLength(96);
    clip1.setExpressionParam(
        ExpressionParamSet.Y_SLIDE_TIMBRE,
        0x60000000,
        List.of(new ParamNode(0, 0x60000000, false)));

    clip0.setSound(synth);
    clip1.setSound(synth);

    assertEquals(
        0x20000000,
        synth
            .paramManager
            .getExpressionParamSet()
            .getParam(ExpressionParamSet.Y_SLIDE_TIMBRE)
            .currentValue);

    clip0.setPos(0);
    clip0.processCurrentPos(0);
    assertEquals(0x20000000, synth.fw2Sound.monophonicExpressionValues[1]);

    // Switching playback to clip1 activates clip1's expression params on synth.paramManager.
    clip1.setPos(0);
    clip1.processCurrentPos(0);
    assertEquals(0x60000000, synth.fw2Sound.monophonicExpressionValues[1]);

    // 2. Kit drum note-on passes per-row mpeValues into Voice.noteOn before initial patching.
    org.deluge.engine.FirmwareKit kit = new org.deluge.engine.FirmwareKit();
    ClipModel kitClip = new ClipModel("KIT_CLIP", 2, 4);
    kitClip.setLoopLength(96);
    kitClip.setSound(kit);
    NoteRowModel drumRow0 = kitClip.getOrCreateRow(0);
    drumRow0.setPitch(0);
    drumRow0.setExpressionParam(
        ExpressionParamSet.Z_PRESSURE, 0x28000000, List.of(new ParamNode(0, 0x28000000, false)));
    kitClip.setStep(0, 0, org.deluge.model.StepData.of(true, 0.8f, 1.0f, 1.0f, 0));

    kitClip.setPos(0);
    kitClip.processCurrentPos(0);
    var drum0 = kit.drumSounds.get(0).fw2Sound;
    assertFalse(drum0.voices.isEmpty());
    assertEquals(0x28000000, drum0.voices.get(0).sourceValues[PatchSource.AFTERTOUCH.ordinal()]);

    // 3. Sound.polyphonicExpressionEventOnChannelOrNote updates arpeggiator.notes MPE values.
    synth.fw2Sound.arpSettings.mode = org.deluge.firmware2.Arpeggiator.ArpMode.ARP;
    synth.triggerNote(64, 100, new int[] {0, 0, 0x1000});
    assertFalse(synth.fw2Sound.arpeggiator.notes.isEmpty());
    assertEquals(0x1000, synth.fw2Sound.arpeggiator.notes.get(0).mpeValues[2]);

    synth.fw2Sound.polyphonicExpressionEventOnChannelOrNote(0x45670000, 2, 64, 0);
    assertEquals(0x4567, synth.fw2Sound.arpeggiator.notes.get(0).mpeValues[2]);
  }
}
