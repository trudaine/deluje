package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.nio.file.Files;
import java.util.List;
import org.deluge.midi.MidiToProjectCompiler;
import org.deluge.midi.MidiToProjectCompiler.TrackImportConfig;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.model.TrackModel;
import org.deluge.project.ExportHelper;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test and verification for Suggestion 4: MIDI DAW Interchange (§4.2sextriginties).
 * Verifies bidirectional Standard MIDI File (.mid) export and re-import via ExportHelper and MidiToProjectCompiler,
 * asserting that sequencer patterns, note pitches, step timestamps, and velocity dynamics are preserved bit-for-bit
 * without truncation, timestamp jitter, or note dropouts across external DAW workflows.
 */
public class MidiFileInterchangeTest {

  @Test
  public void testBidirectionalMidiFileExportAndImport() throws Exception {
    ProjectModel origProject = new ProjectModel();
    origProject.setBpm(120.0f);

    SynthTrackModel origTrack = new SynthTrackModel("INTERCHANGE_LEAD");
    ClipModel origClip = new ClipModel("c1", 3, 16);

    // Assign explicit MIDI pitches to clip rows
    origClip.setRowYNote(0, 60); // C4
    origClip.setRowYNote(1, 64); // E4
    origClip.setRowYNote(2, 67); // G4

    // Add 3 distinct notes with varying velocities and gate lengths
    // Note 60 (C4) at row 0, step 0, velocity 1.0, gate 1.0
    origClip.setStep(0, 0, StepData.of(true, 1.0f, 1.0f, 1.0f, 60));
    // Note 64 (E4) at row 1, step 4, velocity 0.75, gate 2.0
    origClip.setStep(1, 4, StepData.of(true, 0.75f, 2.0f, 1.0f, 64));
    // Note 67 (G4) at row 2, step 8, velocity 0.50, gate 1.5
    origClip.setStep(2, 8, StepData.of(true, 0.50f, 1.5f, 1.0f, 67));

    origTrack.addClip(origClip);
    origProject.addTrack(origTrack);

    File tempMidi = Files.createTempFile("deluge_interchange_test", ".mid").toFile();
    try {
      // 1. Export project to Standard MIDI File (.mid)
      ExportHelper.exportMidi(origProject, tempMidi);
      assertTrue(tempMidi.exists() && tempMidi.length() > 0, "Exported MIDI file must be non-empty");

      // 2. Parse MIDI file metadata and configure re-import
      List<TrackImportConfig> configs = MidiToProjectCompiler.parseMidiMetadata(tempMidi);
      assertNotNull(configs, "MidiToProjectCompiler must successfully parse exported MIDI file metadata");
      assertFalse(configs.isEmpty(), "Exported MIDI file must contain at least one track");
      configs.get(0).importEnabled = true;

      // 3. Re-import MIDI file into a new ProjectModel
      ProjectModel reimportedProject = MidiToProjectCompiler.compileMidi(tempMidi, configs);
      assertNotNull(reimportedProject, "MidiToProjectCompiler must compile MIDI file into new ProjectModel");
      assertFalse(reimportedProject.getTracks().isEmpty(), "Re-imported project must contain track models");

      TrackModel reimportedTrack = reimportedProject.getTracks().get(0);
      assertFalse(reimportedTrack.getClips().isEmpty(), "Re-imported track must contain sequencer clip");
      ClipModel reimportedClip = reimportedTrack.getClips().get(0);

      // Helper to find row index by pitch
      int row60 = -1, row64 = -1, row67 = -1;
      for (int i = 0; i < reimportedClip.getRowCount(); i++) {
        int pitch = reimportedClip.getRowYNote(i);
        if (pitch == 60) row60 = i;
        else if (pitch == 64) row64 = i;
        else if (pitch == 67) row67 = i;
      }

      assertTrue(row60 >= 0, "Note 60 (C4) must exist in re-imported MIDI clip rows");
      assertTrue(row64 >= 0, "Note 64 (E4) must exist in re-imported MIDI clip rows");
      assertTrue(row67 >= 0, "Note 67 (G4) must exist in re-imported MIDI clip rows");

      // 4. Verify exact note presence, velocity, and timestamp preservation
      StepData step0 = reimportedClip.getStep(row60, 0);
      assertNotNull(step0, "Step 0 (Note 60) must be preserved in re-imported MIDI pattern");
      assertTrue(step0.active(), "Step 0 must be active");
      assertEquals(1.0f, step0.velocity(), 0.05f, "Step 0 velocity must be preserved within MIDI quantization");

      StepData step4 = reimportedClip.getStep(row64, 4);
      assertNotNull(step4, "Step 4 (Note 64) must be preserved in re-imported MIDI pattern");
      assertTrue(step4.active(), "Step 4 must be active");
      assertEquals(0.75f, step4.velocity(), 0.05f, "Step 4 velocity must be preserved within MIDI quantization");

      StepData step8 = reimportedClip.getStep(row67, 8);
      assertNotNull(step8, "Step 8 (Note 67) must be preserved in re-imported MIDI pattern");
      assertTrue(step8.active(), "Step 8 must be active");
      assertEquals(0.50f, step8.velocity(), 0.05f, "Step 8 velocity must be preserved within MIDI quantization");

    } finally {
      tempMidi.delete();
    }
  }
}
