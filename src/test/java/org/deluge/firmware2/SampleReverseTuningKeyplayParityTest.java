package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.deluge.engine.DroneLabGenerator;
import org.deluge.model.ClipModel;
import org.deluge.model.KeyplayKeyboard;
import org.deluge.model.ProjectModel;
import org.deluge.model.ScaleMapper;
import org.deluge.model.Scales;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.UndoRedoStack;
import org.junit.jupiter.api.Test;

class SampleReverseTuningKeyplayParityTest {

  @Test
  void sample_getAveragesForCrossfadeReverseNearEndReturnsFalseInsteadOfThrowing() {
    Sample s = new Sample();
    s.numChannels = 2;
    s.byteDepth = 4;
    s.audioDataStartPosBytes = 44;
    s.lengthInSamples = 100;
    s.audioDataLengthBytes = s.lengthInSamples * s.numChannels * s.byteDepth; // 800
    s.data = new int[(int) (s.lengthInSamples * s.numChannels)];
    for (int i = 0; i < s.data.length; i++) {
      s.data[i] = (i + 1) << 16;
    }

    int bytesPerSample = s.numChannels * s.byteDepth; // 8
    int[] totals = new int[TimeStretcher.K_NUM_MOVING_AVERAGES];

    // Near the start of reverse playback (sample 99): readByte starts above audioDataStart + len
    // while endReadByte lands inside [audioDataStart - 1, audioDataStart + len].
    int startByteNearEnd = s.audioDataStartPosBytes + 99 * bytesPerSample;
    assertFalse(
        s.getAveragesForCrossfade(totals, startByteNearEnd, 10, -1, 10),
        "reverse window extending past waveform end must return false without OOB");

    // startBytePos before audioDataStartPosBytes must also return false cleanly.
    assertFalse(
        s.getAveragesForCrossfade(totals, s.audioDataStartPosBytes - 8, 10, 1, 4),
        "startBytePos before audioDataStartPosBytes must return false");

    // Interior reverse window (sample 50) has room on both sides and succeeds.
    int startByteInterior = s.audioDataStartPosBytes + 50 * bytesPerSample;
    assertTrue(
        s.getAveragesForCrossfade(totals, startByteInterior, 10, -1, 4),
        "interior reverse window must succeed");
    assertTrue(totals[0] > 0 && totals[1] > 0 && totals[2] > 0);
  }

  @Test
  void projectModel_calculateNoteFrequenciesAppliesNoteZeroCentsAndFallbackCents() {
    ProjectModel equalModel = new ProjectModel();
    equalModel.setIsEqualTemperament(true);
    equalModel.getCentAdjustForNotesInTemperament()[0] = -25;
    equalModel.calculateNoteFrequencies();

    int expectedFreq0 = (int) Math.round(Math.pow(2.0, -25.0 / 1200.0) * 1027294024.0);
    int expectedInterval0 = (int) Math.round(Math.pow(2.0, -25.0 / 1200.0) * 1073741824.0);
    assertEquals(expectedFreq0, equalModel.noteFrequencyRatio(0), 1);
    assertEquals(expectedInterval0, equalModel.noteIntervalRatio(0), 1);

    // Non-equal temperament with customRatios unset (0.0) but centAdjust populated (as in
    // DroneLabGenerator Just Intonation) must apply centAdjust instead of plain 12-TET.
    ProjectModel jiModel = new ProjectModel();
    jiModel.setIsEqualTemperament(false);
    System.arraycopy(
        DroneLabGenerator.JUST_INTONATION_CENTS,
        0,
        jiModel.getCentAdjustForNotesInTemperament(),
        0,
        12);
    jiModel.calculateNoteFrequencies();

    int e12Tet = LookupTables.noteFrequencyTable[4];
    int eJust = jiModel.noteFrequencyRatio(4); // E is -14 cents in 5-limit JI
    assertTrue(eJust < e12Tet, "Just Intonation E (-14 cents) must be lower than 12-TET E");
  }

  @Test
  void keyplayKeyboard_getNoteInKeyHandlesNegativeScrollOffset() {
    int[] major = Scales.ScaleType.MAJOR.getIntervals(); // {0, 2, 4, 5, 7, 9, 11}
    // Bottom-left pad (trk = 7, col = 0, numRows = 8) has y = 0, so padIndex = scrollOffset.
    // scrollOffset = -1 -> octave = -1, octaveNoteIndex = 6 (interval 11) -> -12 + 60 + 11 = 59.
    assertEquals(59, KeyplayKeyboard.getNoteInKey(7, 0, major, 60, 3, -1, 8));
    // scrollOffset = -7 -> octave = -1, octaveNoteIndex = 0 (interval 0) -> -12 + 60 + 0 = 48.
    assertEquals(48, KeyplayKeyboard.getNoteInKey(7, 0, major, 60, 3, -7, 8));
  }

  @Test
  void scaleMapper_getRowFromPitchWithCustomModeNotesAndRoot() {
    int[] minor = Scales.ScaleType.MINOR.getIntervals();
    int rootD = 2; // D minor
    int row = 65;
    int pitch = ScaleMapper.getRowPitch(row, true, true, false, null, minor, rootD);
    int resolvedRow = ScaleMapper.getRowFromPitch(pitch, true, true, false, null, minor, rootD);
    assertEquals(row, resolvedRow);
  }

  @Test
  void trackModel_removeClipMaintainsValidActiveClipIndex() {
    SynthTrackModel track = new SynthTrackModel("Synth");
    ClipModel c0 = new ClipModel("Clip 0", 8, 16);
    ClipModel c1 = new ClipModel("Clip 1", 8, 16);
    ClipModel c2 = new ClipModel("Clip 2", 8, 16);
    track.addClip(c0);
    track.addClip(c1);
    track.addClip(c2);
    track.setActiveClipIndex(2);
    assertSame(c2, track.getActiveClip());

    // Removing an earlier clip shifts activeClipIndex down so c2 remains active.
    track.removeClip(c0);
    assertEquals(1, track.getActiveClipIndex());
    assertSame(c2, track.getActiveClip());

    // Removing the last (currently active) clip clamps activeClipIndex to the new last clip.
    track.removeClip(c2);
    assertEquals(0, track.getActiveClipIndex());
    assertSame(c1, track.getActiveClip());
  }

  @Test
  void undoRedoStack_replaceLastClearsRedoAndIgnoresReentrantApply() {
    UndoRedoStack stack = new UndoRedoStack(16);
    UndoRedoStack.UndoableAction a1 =
        new UndoRedoStack.UndoableAction() {
          @Override
          public void undo() {}

          @Override
          public void redo() {}

          @Override
          public String getDescription() {
            return "A1";
          }
        };
    UndoRedoStack.UndoableAction a2 =
        new UndoRedoStack.UndoableAction() {
          @Override
          public void undo() {}

          @Override
          public void redo() {}

          @Override
          public String getDescription() {
            return "A2";
          }
        };
    UndoRedoStack.UndoableAction a1Coalesced =
        new UndoRedoStack.UndoableAction() {
          @Override
          public void undo() {}

          @Override
          public void redo() {}

          @Override
          public String getDescription() {
            return "A1-coalesced";
          }
        };

    stack.push(a1);
    stack.push(a2);
    stack.undo();
    assertTrue(stack.canRedo());

    stack.replaceLast(a1Coalesced);
    assertFalse(stack.canRedo(), "replaceLast must invalidate redo history");
    assertEquals("A1-coalesced", stack.getUndoDescription());

    // Re-entrant replaceLast inside undo() must be ignored while applying == true.
    UndoRedoStack.UndoableAction reentrant =
        new UndoRedoStack.UndoableAction() {
          @Override
          public void undo() {
            stack.replaceLast(a2);
          }

          @Override
          public void redo() {}

          @Override
          public String getDescription() {
            return "Reentrant";
          }
        };
    stack.push(reentrant);
    stack.undo();
    assertEquals(
        "A1-coalesced",
        stack.getUndoDescription(),
        "re-entrant replaceLast during undo must be ignored");
    assertTrue(stack.canRedo());
  }
}
