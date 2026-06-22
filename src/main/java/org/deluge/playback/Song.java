package org.deluge.playback;

import java.util.ArrayList;
import java.util.List;
import org.deluge.firmware2.TuningProvider;
import org.deluge.modulation.params.ParamManager;

public class Song extends TimelineCounter implements TuningProvider {
  public List<Clip> clips = new ArrayList<>();
  public int tempoSamples;
  public float tempoBPM;
  public int rootNote;
  public ParamManager paramManager = new ParamManager();
  public int lastSwungTickActioned = 0;
  public int swungTicksTilNextEvent = Integer.MAX_VALUE;
  public boolean inArrangerMode = false;

  public int swingAmount = 0; // -49 to 49
  public int swingInterval = 3; // SyncLevel (e.g. 1/16)

  // ── Microtuning & Custom Temperaments ──
  public static final int OCTAVE_MAX_NUM_MICROTONAL_NOTES = 64;

  public int octaveNumMicrotonalNotes = 12;
  public boolean isEqualTemperament = true;
  public int baseFrequency = 1027294024; // Java standard C-2 base frequency
  public final byte[] centAdjustForNotesInTemperament = new byte[OCTAVE_MAX_NUM_MICROTONAL_NOTES];
  public final int[] noteFrequencyTable = new int[OCTAVE_MAX_NUM_MICROTONAL_NOTES];
  public final int[] noteIntervalTable = new int[OCTAVE_MAX_NUM_MICROTONAL_NOTES];

  // Custom ratio-based temperament (e.g. from Scala .scl files)
  public final double[] customRatios = new double[OCTAVE_MAX_NUM_MICROTONAL_NOTES];

  public Song() {
    tempoBPM = 120.0f;
    calculateNoteFrequencies();
  }

  public void calculateNoteFrequencies() {
    if (isEqualTemperament) {
      noteFrequencyTable[0] = baseFrequency;
      noteIntervalTable[0] = 1073741824; // Q30.ONE
      int numNotesTimes100 = octaveNumMicrotonalNotes * 100;
      for (int i = 1; i < octaveNumMicrotonalNotes; i++) {
        int withCents = i * 100 + centAdjustForNotesInTemperament[i];
        noteFrequencyTable[i] =
            (int) Math.round(Math.pow(2.0, (double) withCents / numNotesTimes100) * baseFrequency);
        noteIntervalTable[i] =
            (int) Math.round(Math.pow(2.0, (double) withCents / numNotesTimes100) * 1073741824.0);
      }
    } else {
      // Ratio-based temperament (e.g., Just Intonation or Scala scale)
      noteFrequencyTable[0] = baseFrequency;
      noteIntervalTable[0] = 1073741824; // Q30.ONE
      for (int i = 1; i < octaveNumMicrotonalNotes; i++) {
        double ratio =
            customRatios[i] > 0.0
                ? customRatios[i]
                : Math.pow(2.0, (double) i / octaveNumMicrotonalNotes);
        noteFrequencyTable[i] = (int) Math.round(ratio * baseFrequency);
        noteIntervalTable[i] = (int) Math.round(ratio * 1073741824.0);
      }
    }
  }

  public static class NoteWithinOctave {
    public int octave;
    public int noteWithin;

    public NoteWithinOctave(int octave, int noteWithin) {
      this.octave = octave;
      this.noteWithin = noteWithin;
    }
  }

  public NoteWithinOctave getOctaveAndNoteWithin(int noteCode) {
    // Math.floorDiv and Math.floorMod perfectly implement divide_round_negative
    int octave = Math.floorDiv(noteCode, octaveNumMicrotonalNotes);
    int noteWithin = Math.floorMod(noteCode, octaveNumMicrotonalNotes);
    return new NoteWithinOctave(octave, noteWithin);
  }

  public int getRootNoteWithinOctave() {
    return getOctaveAndNoteWithin(rootNote).noteWithin;
  }

  // --- firmware2.TuningProvider: lets Voice apply this song's tuning without firmware2 ----------
  // depending on firmware.model. Delegates to the existing per-song tables above.

  @Override
  public int octaveOf(int noteCode) {
    return Math.floorDiv(noteCode, octaveNumMicrotonalNotes);
  }

  @Override
  public int noteWithinOctaveOf(int noteCode) {
    return Math.floorMod(noteCode, octaveNumMicrotonalNotes);
  }

  @Override
  public int noteIntervalRatio(int noteWithinOctave) {
    return noteIntervalTable[noteWithinOctave];
  }

  @Override
  public int noteFrequencyRatio(int noteWithinOctave) {
    return noteFrequencyTable[noteWithinOctave];
  }

  public void addClip(Clip clip) {
    clips.add(clip);
  }

  public void removeClip(Clip clip) {
    clips.remove(clip);
  }

  @Override
  public int getLoopLength() {
    return 0; // Song doesn't have a fixed loop length in this sense
  }

  @Override
  public boolean isPlayingAutomationNow() {
    return true;
  }

  @Override
  public boolean backtrackingCouldLoopBackToEnd() {
    return false;
  }

  public void setBPM(float bpm) {
    this.tempoBPM = bpm;
  }

  public void setRootNote(int rootNote) {
    this.rootNote = rootNote;
  }

  public boolean isInArrangerMode() {
    return inArrangerMode;
  }

  public void doTickForward(int posIncrement) {
    swungTicksTilNextEvent = Integer.MAX_VALUE;

    // Process global song automation
    if (paramManager.mightContainAutomation()) {
      paramManager.processCurrentPos(lastProcessedPos, 0, false, false, true);
      swungTicksTilNextEvent = Math.min(swungTicksTilNextEvent, paramManager.ticksTilNextEvent);
    }

    for (Clip clip : clips) {
      // No incrementing here, that happens in the clock handler
      clip.processCurrentPos(posIncrement);

      if (clip instanceof InstrumentClip) {
        swungTicksTilNextEvent =
            Math.min(swungTicksTilNextEvent, ((InstrumentClip) clip).ticksTilNextEvent);
      }
    }
  }
}
