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

public class HardSyncDiagnosticTest {

  @Test
  public void diagnoseHardSyncPreset() throws Exception {
    System.out.println("=== DIAGNOSING PRESET: 046 Saw Sync ===");

    // Locate the XML preset in target or resources
    File songFile = new File("/Users/ludo/a/chuckjava/deluge/target/ALL_SYNTHS_SONG.xml");
    if (!songFile.exists()) {
      songFile =
          new File(
              System.getProperty("user.home") + "/a/chuckjava/deluge/target/ALL_SYNTHS_SONG.xml");
    }
    if (!songFile.exists()) {
      songFile =
          new File("deluge/src/test/resources/ALL_SYNTHS_SONG.xml"); // repo-relative fallback
    }
    // Local diagnostic: SKIP (not fail) when the generated artifact is absent — it is not committed
    // and is Mac-path-specific. assertTrue here red-builds every other machine/CI.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        songFile.exists(), "ALL_SYNTHS_SONG.xml not present — skipping local hard-sync diagnostic");

    // Load the whole song and find track 46 "046 Saw Sync"
    ProjectModel project = DelugeXmlParser.parseSong(songFile);
    SynthTrackModel targetTrack = null;
    for (var track : project.getTracks()) {
      if (track instanceof SynthTrackModel st && "046 Saw Sync".equalsIgnoreCase(st.getName())) {
        targetTrack = st;
        break;
      }
    }

    assertTrue(targetTrack != null, "Could not find 046 Saw Sync track in ALL_SYNTHS_SONG.xml");
    System.out.println("Loaded track: " + targetTrack.getName());

    // Setup clip to trigger a note (C5 / 72)
    ClipModel clip = new ClipModel("diag_clip", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 72));
    targetTrack.addClip(clip);

    ProjectModel singleTrackProject = new ProjectModel();
    singleTrackProject.setBpm(120.0f);
    singleTrackProject.addTrack(targetTrack);

    ProjectModel compiledSong = FirmwareFactory.createSong(singleTrackProject);
    FirmwareSound sound =
        (FirmwareSound) compiledSong.getTracks().get(0).getActiveClip().getSound();

    // Turn filters OFF to isolate oscillator level from filter attenuation
    sound.fw2Sound.lpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;
    sound.fw2Sound.hpfMode = org.deluge.firmware2.FilterSet.FilterMode.OFF;

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
        "Block | Osc 1 Pos | Osc 2 Pos | Osc 1 Freq | Osc 2 Freq | Env 0 State | Env 0 Val  | Env 2 Val  | Output RMS");
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
        int osc1Pos = voice.unisonParts[0].sources[0].oscPos;
        int osc2Pos = voice.unisonParts[0].sources[1].oscPos;

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
        int env2Val = voice.envelopes[2].lastValue;

        System.out.printf(
            "  %2d  | 0x%08X | 0x%08X | %10.2f | %10.2f | %-11s | 0x%08X | 0x%08X | %.6f%n",
            b, osc1Pos, osc2Pos, freqA, freqB, env0State, env0Val, env2Val, rms);
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
}
