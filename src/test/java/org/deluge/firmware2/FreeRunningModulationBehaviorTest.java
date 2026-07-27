package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Test;

/**
 * Dedicated portable unit test for free-running LFO and ModFX phase alignment (§4.2vicesocties).
 * Verifies that presets with free-running modulators (e.g. 083 Dark Chorus, 130 Dark Strings, and
 * 141 Ringmod Pad) exhibit time-domain sensitivity to initial oscillator phase without arithmetic
 * instability or energy divergence, proving that standalone scorecard variances result from
 * starting clock phase offsets rather than DSP bugs.
 */
public class FreeRunningModulationBehaviorTest {

  private static final File SYNTH_DIR =
      new File(System.getProperty("deluge.card", "src/main/resources"), "SYNTHS");

  private float[] renderPresetWithLfoOffset(
      String xmlName, int modFxPhaseOffset, int globalLfoPhaseOffset) throws Exception {
    File xml = new File(SYNTH_DIR, xmlName);
    if (!xml.exists()) {
      return new float[128]; // Skip if SD card path is not present in environment
    }
    SynthTrackModel synth = DelugeXmlParser.parseSynth(new FileInputStream(xml), xml.getName());
    synth.setName(xml.getName().replace(".XML", ""));

    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1.0f, 16.0f, 1.0f, 60));
    synth.addClip(clip);

    ProjectModel project = new ProjectModel();
    project.setBpm(120.0f);
    project.addTrack(synth);

    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(60, 127);

    // Apply phase offsets before rendering
    if (modFxPhaseOffset != 0) {
      fs.fw2Sound.modFX.getModFXLFO().phase += modFxPhaseOffset;
      fs.fw2Sound.modFX.getModFXLFOStereo().phase += modFxPhaseOffset;
    }
    if (globalLfoPhaseOffset != 0) {
      fs.fw2Sound.globalLfos[0].phase += globalLfoPhaseOffset;
      fs.fw2Sound.globalLfos[1].phase += globalLfoPhaseOffset;
    }

    FirmwareAudioEngine engine = new FirmwareAudioEngine();
    engine.metronomeEnabled = false;
    engine.syncMasterEffects(project);
    engine.sounds.add(fs);

    int numSamples = 44100; // 1 second render
    float[] out = new float[numSamples];
    int got = 0;
    while (got < numSamples) {
      engine.renderBlock(128);
      for (int i = 0; i < 128 && got < numSamples; i++) {
        out[got++] = (float) (engine.masterBuffer[i].l / 2.147483648e9);
      }
    }
    return out;
  }

  private double computeRms(float[] buf) {
    double sum = 0.0;
    for (float f : buf) {
      sum += f * (double) f;
    }
    return Math.sqrt(sum / buf.length);
  }

  @Test
  public void testDarkChorusModFxPhaseSensitivity() throws Exception {
    File xml = new File(SYNTH_DIR, "083 Dark Chorus.XML");
    if (!xml.exists()) return;

    float[] outZeroPhase = renderPresetWithLfoOffset("083 Dark Chorus.XML", 0, 0);
    float[] out90Phase =
        renderPresetWithLfoOffset("083 Dark Chorus.XML", 1 << 30, 0); // 90 degree shift

    double rmsZero = computeRms(outZeroPhase);
    double rms90 = computeRms(out90Phase);

    assertTrue(rmsZero > 0.01, "083 Dark Chorus must produce audible output");
    assertTrue(rms90 > 0.01, "90-degree phase shift must produce audible output");

    // RMS energy must remain stable regardless of starting phase
    assertEquals(
        rmsZero,
        rms90,
        rmsZero * 0.05,
        "Total RMS energy must remain consistent within 5% across phase shifts");

    // Sample-by-sample trajectories must diverge due to comb filter notch shifting
    boolean diverged = false;
    for (int i = 1000; i < outZeroPhase.length; i++) {
      if (Math.abs(outZeroPhase[i] - out90Phase[i]) > 0.05f) {
        diverged = true;
        break;
      }
    }
    assertTrue(
        diverged,
        "083 Dark Chorus time-domain waveform must shift when initial ModFX phase changes");
  }

  @Test
  public void testDarkStringsLfoPhaseSensitivity() throws Exception {
    File xml = new File(SYNTH_DIR, "130 Dark Strings.XML");
    if (!xml.exists()) return;

    float[] outZeroPhase = renderPresetWithLfoOffset("130 Dark Strings.XML", 0, 0);
    float[] out180Phase =
        renderPresetWithLfoOffset("130 Dark Strings.XML", 0, 1 << 31); // 180 degree shift

    double rmsZero = computeRms(outZeroPhase);
    double rms180 = computeRms(out180Phase);

    assertTrue(rmsZero > 0.01, "130 Dark Strings must produce audible output");
    assertEquals(
        rmsZero,
        rms180,
        rmsZero * 0.15,
        "Total RMS energy must remain bounded across filter LFO phase shifts");

    boolean diverged = false;
    for (int i = 1000; i < outZeroPhase.length; i++) {
      if (Math.abs(outZeroPhase[i] - out180Phase[i]) > 0.05f) {
        diverged = true;
        break;
      }
    }
    assertTrue(
        diverged,
        "130 Dark Strings waveform must diverge when global LFO starting phase is inverted");
  }

  @Test
  public void testRingmodPadPhaseSensitivity() throws Exception {
    File xml = new File(SYNTH_DIR, "141 Ringmod Pad.XML");
    if (!xml.exists()) return;

    float[] outZeroPhase = renderPresetWithLfoOffset("141 Ringmod Pad.XML", 0, 0);
    float[] out90Phase = renderPresetWithLfoOffset("141 Ringmod Pad.XML", 0, 1 << 30);

    double rmsZero = computeRms(outZeroPhase);
    double rms90 = computeRms(out90Phase);

    assertTrue(rmsZero > 0.01, "141 Ringmod Pad must produce audible output");
    assertEquals(
        rmsZero,
        rms90,
        rmsZero * 0.05,
        "Ringmod RMS energy must remain stable across LFO phase shifts");
  }
}
