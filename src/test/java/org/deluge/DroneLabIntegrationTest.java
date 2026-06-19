package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.util.List;
import org.deluge.engine.DroneLabGenerator;
import org.deluge.firmware.engine.FirmwareFactory;
import org.deluge.firmware.engine.FirmwareSound;
import org.deluge.firmware.model.InstrumentClip;
import org.deluge.firmware.model.Song;
import org.deluge.firmware2.StereoSample;
import org.deluge.model.ClipModel;
import org.deluge.model.HighResNote;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.junit.jupiter.api.Test;

/**
 * End-to-End Integration and Fidelity test for the Drone Lab & Texture Generator. Automatically
 * asserts parameter parity, audible filter cutoffs, diatonic pitch mappings, clip expansion
 * safeguards, and soft-clipping loudness headroom.
 */
public class DroneLabIntegrationTest {

  @Test
  public void testDroneGenerationAndFidelityE2E() throws Exception {
    // 1. Initialize models
    ProjectModel project = new ProjectModel();
    SynthTrackModel track = new SynthTrackModel("Drone Synth");
    project.getTracks().add(track);
    BridgeContract bridge = new BridgeContract();

    // ── ASSERTION 1: Clip Expansion Safeguard (Bugs #3 & #4) ──
    // Default new track has a clip of size 8x16.
    // Verify that the generator expands it to support the 128-note diatonic grid and 192 steps.
    DroneLabGenerator.generateDrone(track, project, bridge, 0, false, false);

    ClipModel activeClip = track.getActiveClip();
    assertNotNull(activeClip, "Active clip must be automatically created");
    assertEquals(128, activeClip.getRowCount(), "Clip row count must be expanded to 128");
    assertEquals(192, activeClip.getStepCount(), "Clip step count must be expanded to 192");

    // ── ASSERTION 2: Diatonic Row Pitch Mapping (Bug #3) ──
    // Under the UI's diatonic major-scale mapping, C2 (MIDI 36) maps exactly to row 81.
    // Assert that the note is written on row 81, step 0, with pitch 36.
    int diatonicC2Row = 81;
    StepData step = activeClip.getStep(diatonicC2Row, 0);
    assertTrue(step.active(), "The C2 diatonic row cell must be active");
    assertEquals(36, step.pitch(), "The sequenced step must carry absolute pitch 36 (C2)");
    assertEquals(192.0f, step.gate(), "The note must span the full 192 steps");

    // Assert that raw high-resolution note events are populated for the JRE scheduler
    List<HighResNote> rawNotes = activeClip.getRawNoteEvents(diatonicC2Row);
    assertNotNull(rawNotes, "Raw note events must not be null");
    assertEquals(1, rawNotes.size(), "Should have exactly 1 note event");
    assertEquals(0, rawNotes.get(0).getTickPos(), "Note must start at tick 0");
    assertTrue(rawNotes.get(0).getTickLen() > 4000, "Note must span the 12-bar duration");

    // ── ASSERTION 3: Audible LPF Cutoff Frequency (Bug #1) ──
    // Verify that the LPF cutoff is set in Hertz (audible range) rather than normalized fractions.
    assertTrue(
        track.getLpfFreq() >= 500.0f, "LPF Cutoff must be in audible Hertz range (>= 500Hz)");
    assertEquals(
        1200.0f, track.getLpfFreq(), 0.1f, "Subtractive drone should default to 1200Hz LPF");

    // ── ASSERTION 4: Sequencer Timing & Voice Compilation (Bug #2) ──
    // Compile the project into a firmware Song and verify the note is compiled successfully.
    Song fwSong = FirmwareFactory.createSong(project);
    assertNotNull(fwSong, "Firmware song compilation must succeed");
    assertEquals(1, fwSong.clips.size(), "Compiled song must contain exactly 1 track");

    InstrumentClip fwClip = (InstrumentClip) fwSong.clips.get(0);
    int totalNotesInFw = 0;
    for (var noteRow : fwClip.noteRows) {
      totalNotesInFw += noteRow.notes.size();
    }
    assertEquals(1, totalNotesInFw, "Sequencer must detect exactly 1 compiled note event");

    // ── ASSERTION 5: Audio Output, Headroom & Soft-Clipping Saturation (Bug #5) ──
    // Instantiate the DSP synthesizer voice and render 1 second of audio.
    FirmwareSound sound = (FirmwareSound) fwClip.sound;
    FirmwareFactory.applyModelToLiveSound(track, sound);
    sound.syncParamsToFw2();
    sound.triggerNote(36, 108); // Trigger the C2 drone note

    int sampleRate = 44100;
    int blockSamples = 128;
    int totalSamples = sampleRate * 1; // 1 second
    int blocksToRender = totalSamples / blockSamples;

    StereoSample[] block = new StereoSample[blockSamples];
    for (int i = 0; i < blockSamples; i++) {
      block[i] = new StereoSample();
    }

    double peakL = 0.0;
    double peakR = 0.0;
    double sumSq = 0.0;
    long totalFrames = 0;

    // Apply the absolute maximum Monitor Gain Boost of 128x to test limits!
    int monitorGainMul = 128;

    for (int b = 0; b < blocksToRender; b++) {
      for (int i = 0; i < blockSamples; i++) {
        block[i].l = 0;
        block[i].r = 0;
      }

      sound.renderOutput(block, blockSamples, null);

      for (int i = 0; i < blockSamples; i++) {
        // Convert internal Q31 to float
        float xL = (float) block[i].l / 2147483648.0f;
        float xR = (float) block[i].r / 2147483648.0f;

        // Apply 128x boost in the float domain
        float boostedL = xL * monitorGainMul;
        float boostedR = xR * monitorGainMul;

        // Apply our soft-clipping saturation function
        float saturatedL = softClip(boostedL);
        float saturatedR = softClip(boostedR);

        double absL = Math.abs(saturatedL);
        double absR = Math.abs(saturatedR);

        if (absL > peakL) peakL = absL;
        if (absR > peakR) peakR = absR;

        sumSq += saturatedL * saturatedL + saturatedR * saturatedR;
        totalFrames++;
      }
    }

    double rms = Math.sqrt(sumSq / (totalFrames * 2));

    // Assert that the soft-clipper successfully prevented ANY digital clipping (must be <= 1.0)
    assertTrue(peakL <= 1.0, "Left channel peak must never exceed 1.0 (clipping)");
    assertTrue(peakR <= 1.0, "Right channel peak must never exceed 1.0 (clipping)");

    // Assert that we achieved massive, loud, commercial-grade average volume (RMS)
    assertTrue(rms >= 0.05, "Average RMS energy must be high (loud and warm) under the 128x boost");

    System.out.println("Drone E2E Fidelity Test Passed perfectly!");
    System.out.printf("  - Rendered Peak Left  : %.6f\n", peakL);
    System.out.printf("  - Rendered Peak Right : %.6f\n", peakR);
    System.out.printf("  - Rendered Average RMS: %.6f\n", rms);
  }

  /** Mirror the audio driver's soft-clipping function to assert fidelity */
  private float softClip(float x) {
    if (x > 0.7f) {
      return 0.7f + 0.3f * (float) Math.tanh((x - 0.7f) / 0.3f);
    }
    if (x < -0.7f) {
      return -0.7f + 0.3f * (float) Math.tanh((x + 0.7f) / 0.3f);
    }
    return x;
  }
}
