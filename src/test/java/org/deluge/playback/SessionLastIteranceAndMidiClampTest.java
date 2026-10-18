package org.deluge.playback;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.deluge.midi.MIDIMessage;
import org.deluge.midi.MidiEngine;
import org.deluge.midi.MidiTransport;
import org.deluge.model.ArmState;
import org.deluge.model.ClipModel;
import org.deluge.model.Iterance;
import org.deluge.model.NoteModel;
import org.deluge.model.NoteRowModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.SynthTrackModel;
import org.deluge.shadow.midi.MidiMsg;
import org.junit.jupiter.api.Test;

/**
 * Tests for {@code Session::willClipContinuePlayingAtEnd} ({@code session.cpp:2730-2762}) driving
 * {@link Iterance#LAST} ({@code instrument_clip.cpp:718-719}) and MIDI expression data-byte
 * saturation ({@code message.cpp:21-23}, {@code midi_engine.cpp:444-495}).
 */
class SessionLastIteranceAndMidiClampTest {

  private static final class RecordingSound extends FirmwareSound {
    final List<Integer> triggeredLoops = new ArrayList<>();
    int currentLoop = 0;

    @Override
    public void triggerNote(int note, int vel) {
      if (note == 60) {
        triggeredLoops.add(currentLoop);
      }
    }

    @Override
    public void releaseNote(int note) {}
  }

  private static ClipModel makeClipWithLastNote(
      String name, int steps, String launchStyle, int noteStep, RecordingSound sound) {
    ClipModel clip = new ClipModel(name, 4, steps);
    clip.setLaunchStyle(launchStyle);
    clip.setSound(sound);
    NoteRowModel row = clip.getOrCreateRow(0);
    row.setPitch(60);
    NoteModel note = new NoteModel(noteStep * 24, 12, 1.0f, 1.0f, 0);
    note.setIterance(Iterance.LAST);
    row.getNotes().add(note);
    return clip;
  }

  @Test
  void iteranceLast_playsOnlyOnFinalLoopBeforeQuantizedStop() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    RecordingSound sound = new RecordingSound();
    ClipModel clip = makeClipWithLastNote("Main", 4, "default", 2, sound); // 96 ticks, note at 48
    track.addClip(clip);
    project.addTrack(track);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Loop 0 (ticks 0..95): no launch scheduled -> LAST note at tick 48 stays silent.
    sound.currentLoop = 0;
    ph.advanceTicks(96);
    assertEquals(List.of(), sound.triggeredLoops, "LAST note must not play while clip will loop");

    // Loop 1: at tick 108 (12 ticks into loop 1), arm clip to stop at end of loop 1 (tick 192).
    sound.currentLoop = 1;
    ph.advanceTicks(12);
    ph.toggleClipStatus(track, clip, false);
    assertEquals(ArmState.ON_NORMAL, clip.getArmState());
    assertEquals(192L, ph.getLaunchEventAtSwungTickCount());
    assertFalse(ph.willClipContinuePlayingAtEnd(track, clip));

    // Advance past tick 144 (pos 48 of loop 1) to tick 192: LAST note fires in loop 1, clip stops.
    ph.advanceTicks(84);
    assertEquals(192, ph.lastSwungTickActioned);
    assertFalse(clip.isActiveInSession());
    assertEquals(List.of(1), sound.triggeredLoops, "LAST note must play on final loop before stop");
  }

  @Test
  void iteranceLast_playsDuringOnceClipSingleLoop() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    RecordingSound baseSound = new RecordingSound();
    ClipModel baseClip = makeClipWithLastNote("Base", 4, "default", 1, baseSound);
    RecordingSound onceSound = new RecordingSound();
    ClipModel onceClip = makeClipWithLastNote("Once", 4, "once", 1, onceSound);
    onceClip.setActiveInSession(false);
    track.addClip(baseClip);
    track.addClip(onceClip);
    project.addTrack(track);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Arm onceClip at tick 12 to launch at tick 96.
    ph.advanceTicks(12);
    ph.toggleClipStatus(track, onceClip, false);
    ph.advanceTicks(84); // -> tick 96, onceClip starts and arms itself to stop at tick 192
    assertTrue(onceClip.isActiveInSession());
    assertEquals(ArmState.ON_NORMAL, onceClip.getArmState());
    assertFalse(ph.willClipContinuePlayingAtEnd(track, onceClip));

    // Advance through onceClip's single 96-tick pass: its LAST note at pos 24 must play.
    onceSound.currentLoop = 0;
    ph.advanceTicks(96);
    assertFalse(onceClip.isActiveInSession());
    assertEquals(
        List.of(0), onceSound.triggeredLoops, "ONCE clip's single pass is its final iteration");
  }

  @Test
  void willClipContinuePlayingAtEnd_respectsRepeatsAndReverseDirection() {
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Synth");
    RecordingSound sound = new RecordingSound();
    ClipModel clip = makeClipWithLastNote("Rev", 4, "default", 1, sound);
    clip.setPlayDirection(ClipModel.PlayDirection.REVERSE);
    track.addClip(clip);
    project.addTrack(track);

    PlaybackHandler ph = new PlaybackHandler();
    ph.setProject(project);
    ph.start();

    // Advance 24 ticks into reversed clip (lastProcessedPos = 72, ticksTilReachLoopPoint = 72).
    ph.advanceTicks(24);
    assertEquals(72, clip.lastProcessedPos);
    clip.setArmState(ArmState.ON_NORMAL);

    // Scheduled 2 repeats out -> will continue playing at end.
    ph.setLaunchEvent(ph.lastSwungTickActioned + 72, 2);
    assertTrue(ph.willClipContinuePlayingAtEnd(track, clip));

    // Scheduled 1 repeat out, exactly at the loop point (72 ticks away) -> will NOT continue.
    ph.setLaunchEvent(ph.lastSwungTickActioned + 72, 1);
    assertFalse(ph.willClipContinuePlayingAtEnd(track, clip));

    // Scheduled 1 repeat out, AFTER the loop point (73 ticks away) -> WILL continue.
    ph.setLaunchEvent(ph.lastSwungTickActioned + 73, 1);
    assertTrue(ph.willClipContinuePlayingAtEnd(track, clip));
  }

  @Test
  void midiDataByte_clampsCcAndAftertouchToSevenBits() {
    assertEquals(0, MIDIMessage.toDataByte(-1));
    assertEquals(0, MIDIMessage.toDataByte(Integer.MIN_VALUE));
    assertEquals(64, MIDIMessage.toDataByte(64));
    assertEquals(127, MIDIMessage.toDataByte(127));
    assertEquals(127, MIDIMessage.toDataByte(128));
    assertEquals(127, MIDIMessage.toDataByte(Integer.MAX_VALUE));

    // MIDIMessage factories clamp out-of-range values instead of wrapping via & 0x7F.
    assertEquals(127, MIDIMessage.cc(0, 74, 200).data2());
    assertEquals(0, MIDIMessage.cc(0, 74, -10).data2());
    assertEquals(127, MIDIMessage.polyAftertouch(0, 60, 128).data2());
    assertEquals(0, MIDIMessage.polyAftertouch(0, 60, -1).data2());
    assertEquals(127, MIDIMessage.channelAftertouch(0, 300).data1());
    assertEquals(0, MIDIMessage.channelAftertouch(0, -5).data1());

    // MidiEngine send methods forward clamped data bytes to transports.
    MidiEngine engine = new MidiEngine();
    List<MidiMsg> sent = new ArrayList<>();
    MidiTransport capture =
        new MidiTransport() {
          @Override
          public String getPortName() {
            return "capture";
          }

          @Override
          public boolean sendMessage(MidiMsg msg) {
            sent.add(msg);
            return true;
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
          public void close() {}
        };
    engine.addOutputTransport(capture);

    engine.sendCC(2, 74, 140);
    engine.sendCC(2, 74, -3);
    engine.sendChannelAftertouch(2, 250);
    engine.sendPolyphonicAftertouch(2, 60, -8);

    assertEquals(4, sent.size());
    assertEquals(127, sent.get(0).data3, "CC value > 127 clamps to 127");
    assertEquals(0, sent.get(1).data3, "CC value < 0 clamps to 0");
    assertEquals(127, sent.get(2).data2, "Channel aftertouch > 127 clamps to 127");
    assertEquals(0, sent.get(3).data3, "Poly aftertouch < 0 clamps to 0");
  }
}
