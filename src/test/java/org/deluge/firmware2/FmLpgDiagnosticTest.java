package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

public class FmLpgDiagnosticTest {

  @Test
  public void diagnoseFmLpgPreset() throws Exception {
    System.out.println("=== DIAGNOSING PRESET: 107 FM LPG Percussion ===");

    // Locate the XML preset in resources
    File presetFile = new File("src/main/resources/SYNTHS/107 FM LPG Percussion.XML");
    assertTrue(presetFile.exists(), "Preset XML not found at " + presetFile.getAbsolutePath());

    // Load the single synth track model
    SynthTrackModel targetTrack = DelugeXmlParser.parseSynth(presetFile);
    System.out.println("Loaded track: " + targetTrack.getName());

    // Setup clip to trigger a note (C5 / 72)
    ClipModel clip = new ClipModel("diag_clip", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 72));
    targetTrack.addClip(clip);

    ProjectModel singleTrackProject = new ProjectModel();
    singleTrackProject.setBpm(120.0f);
    singleTrackProject.addTrack(targetTrack);

    // Compile DSP Sound Engines
    ProjectModel compiledSong = FirmwareFactory.createSong(singleTrackProject);
    FirmwareSound sound =
        (FirmwareSound) compiledSong.getTracks().get(0).getActiveClip().getSound();

    // Trigger note and render blocks
    sound.triggerNote(72, 100);
    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(sound);

    // Let's render 30 blocks (3840 samples) and inspect the oscillator phases and outputs
    int totalSamples = 3840;
    int blockSize = 128;
    float[] sw = new float[totalSamples];
    int got = 0;

    System.out.println("\nBlock-by-Block Diagnostic:");
    System.out.println(
        "Block | Osc 1 Freq | Osc 2 Freq | Env 0 State | Env 0 Val  | Env 1 Val  | LPF Cutoff | HPF Cutoff | Output RMS");
    System.out.println(
        "----------------------------------------------------------------------------------------------------------------");

    for (int b = 0; b < totalSamples / blockSize; b++) {
      engine.renderBlock(blockSize);

      // Compute RMS of this block
      double sum = 0;
      for (int i = 0; i < blockSize; i++) {
        float val = (float) (engine.masterBuffer[i].l / 2147483648.0);
        sw[got++] = val;
        sum += val * val;
      }
      double rms = Math.sqrt(sum / blockSize);

      // Inspect low-level voice state
      if (!sound.fw2Sound.voices.isEmpty()) {
        var voice = sound.fw2Sound.voices.get(0);

        // Calculate exact modulated phase increments
        int pIncA = voice.unisonParts[0].sources[0].phaseIncrementStoredValue;
        pIncA = voice.adjustPitch(pIncA, voice.overallPitchAdjust);
        pIncA = voice.adjustPitch(pIncA, voice.paramFinalValues[Param.LOCAL_OSC_A_PITCH_ADJUST]);

        int pIncB = voice.unisonParts[0].sources[1].phaseIncrementStoredValue;
        pIncB = voice.adjustPitch(pIncB, voice.overallPitchAdjust);
        pIncB = voice.adjustPitch(pIncB, voice.paramFinalValues[Param.LOCAL_OSC_B_PITCH_ADJUST]);

        double freqA = ((double) pIncA * 44100.0) / 4294967296.0;
        double freqB = ((double) pIncB * 44100.0) / 4294967296.0;

        var env0State = voice.envelopes[0].state;
        int env0Val = voice.envelopes[0].lastValue;
        int env1Val = voice.envelopes[1].lastValue;

        int lpfCutoff = voice.paramFinalValues[Param.LOCAL_LPF_FREQ];
        int hpfCutoff = voice.paramFinalValues[Param.LOCAL_HPF_FREQ];

        System.out.printf(
            "  %2d  | %10.2f | %10.2f | %-11s | 0x%08X | 0x%08X | 0x%08X | 0x%08X | %.6f%n",
            b, freqA, freqB, env0State, env0Val, env1Val, lpfCutoff, hpfCutoff, rms);
      } else {
        System.out.printf(
            "  %2d  | (no active voice)                                                                            | %.6f%n",
            b, rms);
      }
    }

    double totalRms = 0;
    for (float v : sw) totalRms += v * v;
    totalRms = Math.sqrt(totalRms / sw.length);
    System.out.println("\nTotal rendered RMS: " + totalRms);
    assertTrue(totalRms > 0.001, "Rendered output must not be silent");
  }

  @Test
  public void testFmLpgAsKitDrumSlot() throws Exception {
    System.out.println("\n=== TESTING PRESET 107 AS A DRUM KIT SLOT ===");

    // 1. Locate the XML preset in resources
    File presetFile = new File("src/main/resources/SYNTHS/107 FM LPG Percussion.XML");
    assertTrue(presetFile.exists(), "Preset XML not found");

    // 2. Parse the XML preset directly into a SoundDrum model (our new public API!)
    org.deluge.model.SoundDrum sd = DelugeXmlParser.parseSoundDrum(presetFile);
    System.out.println("Parsed SoundDrum: " + sd.getName());
    System.out.println("  - Osc 1 Type: " + sd.getOsc1Type());
    System.out.println("  - Osc 2 Type: " + sd.getOsc2Type());
    System.out.println("  - Synth Mode: " + sd.getSynthMode() + " (1 = FM)");

    // 3. Create a KitTrackModel and add the drum slot
    org.deluge.model.KitTrackModel kitTrack = new org.deluge.model.KitTrackModel("Diag Kit");
    kitTrack.addDrum(sd);

    // Setup clip to trigger the drum slot (which corresponds to drumIndex 0, note 60 by default for
    // slot 0!)
    ClipModel clip = new ClipModel("kit_clip", 1, 16);
    // In Deluge kit tracks, the first drum slot is triggered at note 60 (y=60)
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    kitTrack.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(kitTrack);

    // 4. Compile the Kit Track
    ProjectModel compiledSong = FirmwareFactory.createSong(project);
    org.deluge.engine.FirmwareKit kit =
        (org.deluge.engine.FirmwareKit) compiledSong.getTracks().get(0).getActiveClip().getSound();

    // Trigger the drum note and render
    kit.triggerDrum(0, 100);

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.sounds.add(kit);

    // Render 30 blocks
    int totalSamples = 3840;
    int blockSize = 128;
    float[] sw = new float[totalSamples];
    int got = 0;

    for (int b = 0; b < totalSamples / blockSize; b++) {
      engine.renderBlock(blockSize);
      for (int i = 0; i < blockSize; i++) {
        float val = (float) (engine.masterBuffer[i].l / 2147483648.0);
        sw[got++] = val;
      }
    }

    double totalRms = 0;
    for (float v : sw) totalRms += v * v;
    totalRms = Math.sqrt(totalRms / sw.length);
    System.out.println("Rendered Kit Drum Slot RMS: " + totalRms);
    assertTrue(totalRms > 0.001, "Drum synthesis slot output must not be silent!");
  }
}
