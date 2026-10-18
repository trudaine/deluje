package org.deluge.model;

import static org.junit.jupiter.api.Assertions.assertEquals;

import java.util.ArrayList;
import java.util.List;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Oscillator.OscType;
import org.deluge.firmware2.Param;
import org.deluge.firmware2.Sound;
import org.junit.jupiter.api.Test;

/**
 * C NoteRow::resumePlayback → attemptLateStartOfNextNoteToPlay (note_row.cpp:2440-2511, 3155-3170)
 * with CatchNotes on: un-muting a row inside a note, or adding a note under the playhead, starts it
 * late — part-way in when the sound allowsVeryLateNoteStart (sound.cpp:2055-2096), otherwise only
 * within kAmountNoteOnLatenessAllowed (2205 samples) and from its start.
 */
class LateNoteStartTest {

  static final class LateRecordingSound extends FirmwareSound {
    final List<String> events = new ArrayList<>();

    @Override
    public void triggerNote(int note, int vel) {
      events.add("ON");
    }

    @Override
    public void triggerNoteLate(int note, int vel, int samplesLate) {
      events.add("LATE:" + samplesLate);
    }

    @Override
    public void releaseNote(int note) {
      events.add("OFF");
    }
  }

  private static LateRecordingSound loopingSampleSound() {
    LateRecordingSound s = new LateRecordingSound();
    org.deluge.playback.Sample smp = new org.deluge.playback.Sample();
    smp.numChannels = 1;
    smp.byteDepth = 2;
    smp.sampleRate = 44100;
    smp.data = new float[44100];
    s.oscTypes[0] = OscType.SAMPLE;
    s.samples[0] = smp;
    s.sampleSettings[0].loopMode = Sound.REPEAT_LOOP;
    s.paramNeutralValues[Param.LOCAL_OSC_A_VOLUME] = Integer.MAX_VALUE;
    s.paramNeutralValues[Param.LOCAL_OSC_B_VOLUME] = Integer.MIN_VALUE;
    return s;
  }

  /** Plays to {@code unmuteAt} with the row muted, un-mutes, then runs one more tick. */
  private static List<String> unmuteAt(LateRecordingSound sound, int unmuteAt) {
    ClipModel clip = new ClipModel("c", 1, 4); // 96 ticks
    clip.setSound(sound);
    NoteRowModel row = clip.getOrCreateRow(0);
    row.setPitch(60);
    row.getNotes().add(new NoteModel(0, 48, 1.0f, 1.0f, 0));
    row.setMuted(true);
    clip.setPos(0);
    clip.processCurrentPos(0);
    for (int t = 0; t < unmuteAt; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
    row.setMuted(false);
    clip.incrementPos(1);
    clip.processCurrentPos(1);
    return sound.events;
  }

  @Test
  void loopingSampleStartsPartWayIn() {
    // Un-muted at pos 20 + 1 tick: 21 ticks into the note at 120 bpm (229.6875 samples/tick).
    assertEquals(List.of("LATE:" + (int) (21 * 229.6875)), unmuteAt(loopingSampleSound(), 20));
  }

  @Test
  void waveSynthStartsOnlyWithin50msAndFromItsStart() {
    // 6 ticks = 1378 samples < 2205: starts, but from the beginning (samplesLate 0).
    assertEquals(List.of("ON"), unmuteAt(new LateRecordingSound(), 5));
    // 21 ticks = 4823 samples: too late for a wave oscillator.
    assertEquals(List.of(), unmuteAt(new LateRecordingSound(), 20));
  }

  @Test
  void noteAddedUnderThePlayheadStartsLate() {
    LateRecordingSound sound = loopingSampleSound();
    ClipModel clip = new ClipModel("c", 1, 4);
    clip.setSound(sound);
    clip.setPos(0);
    clip.processCurrentPos(0);
    for (int t = 0; t < 30; t++) {
      clip.incrementPos(1);
      clip.processCurrentPos(1);
    }
    // Step 1 = [24, 48): the playhead (30) is inside it.
    clip.setStep(0, 1, StepData.of(true, 1.0f, 1.0f, 1.0f, 60));
    clip.incrementPos(1);
    clip.processCurrentPos(1);
    assertEquals(List.of("LATE:" + (int) (7 * 229.6875)), sound.events);
  }
}
