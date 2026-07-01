package org.deluge;

import java.io.File;
import java.io.FileInputStream;
import org.deluge.engine.FirmwareAudioEngine;
import org.deluge.engine.FirmwareFactory;
import org.deluge.engine.FirmwareSound;
import org.deluge.firmware2.Voice;
import org.deluge.model.ClipModel;
import org.deluge.model.ProjectModel;
import org.deluge.model.StepData;
import org.deluge.model.SynthTrackModel;
import org.deluge.xml.DelugeXmlParser;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;

/**
 * Fidelity scorecard for the hand-authored single-feature test presets
 * (src/test/resources/fidelity/test_presets) against their hardware references
 * (src/test/resources/fidelity/preset_refs), recorded at the Deluge's "C5" = MIDI note 84
 * (1046 Hz). Renders each preset at note 84 and reports a log-spectrum cosine (loudest-window) —
 * the same amplitude-invariant metric as FidelityScorecardTest. Self-skips if the refs are absent.
 *
 * <p>Run: {@code mvn test -Dtest=PresetScorecardTest -Dgpg.skip=true}
 */
public class PresetScorecardTest {
  static final int SR = 44100;
  // Render note. Default 84 = the Deluge's "C5" = 1046 Hz, the octave the current references were
  // recorded at. Override with -Dpreset.note=60 when comparing lower-note re-records (a lower note
  // gives the subtractive oscillators rich harmonics, so the spectral metric is far more meaningful
  // than at 1046 Hz where they are nearly sinusoidal).
  static final int NOTE = Integer.getInteger("preset.note", 84);
  static final File PRESETS = new File("src/test/resources/fidelity/test_presets");
  // Reference dir: preset_refs (note 84) by default; -Dpreset.refs=preset_refs_c4 for a lower set.
  static final File REFS =
      new File("src/test/resources/fidelity/" + System.getProperty("preset.refs", "preset_refs"));

  @Test
  @Tag("slow")
  void scorecard() throws Exception {
    Assumptions.assumeTrue(REFS.isDirectory(), "preset_refs not present");
    File[] refs = REFS.listFiles((d, n) -> n.startsWith("reference_T") && n.endsWith(".wav"));
    Assumptions.assumeTrue(refs != null && refs.length > 0, "no preset references");
    java.util.Arrays.sort(refs);
    java.util.List<double[]> scores = new java.util.ArrayList<>();
    System.out.printf("%n%-26s %s%n", "preset", "spectral cosine (note 84)");
    for (File ref : refs) {
      String name = ref.getName().replace("reference_", "").replace(".wav", "");
      File xml = new File(PRESETS, name + ".XML");
      if (!xml.isFile()) continue;
      float[] hw = FidelityScorecardTest.readWavMono(ref);
      float[] our = render(xml);
      double cos =
          FidelityScorecardTest.cosine(
              FidelityScorecardTest.spectrum(our, loud(our), SR),
              FidelityScorecardTest.spectrum(hw, loud(hw), SR));
      scores.add(new double[] {cos});
      System.out.printf("  %-24s %.3f%s%n", name, cos, cos < 0.6 ? "   <<< low" : "");
    }
    double[] s = scores.stream().mapToDouble(a -> a[0]).sorted().toArray();
    double mean = java.util.Arrays.stream(s).average().orElse(0);
    double median = s.length == 0 ? 0 : s[s.length / 2];
    long ge80 = java.util.Arrays.stream(s).filter(v -> v >= 0.8).count();
    System.out.printf(
        "%n=== PRESET SCORECARD (note 84) ===%n  n=%d  mean=%.3f  median=%.3f  >=0.80: %d%n",
        s.length, mean, median, ge80);
  }

  static float[] render(File xml) throws Exception {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    Voice.testStartPhaseOverrideOsc1.set(0);
    Voice.testStartPhaseOverrideOsc2.set(0);
    SynthTrackModel synth = DelugeXmlParser.parseSynth(xml);
    synth.setName(xml.getName());
    ClipModel clip = new ClipModel("c", 1, 16);
    clip.setStep(0, 0, StepData.of(true, 1f, 16f, 1f, NOTE));
    synth.addClip(clip);
    ProjectModel project = new ProjectModel();
    project.addTrack(synth);
    ProjectModel song = FirmwareFactory.createSong(project);
    FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
    fs.triggerNote(NOTE, 100);
    FirmwareAudioEngine e = new FirmwareAudioEngine();
    e.sounds.add(fs);
    int n = SR * 3;
    float[] out = new float[n];
    int g = 0;
    while (g < n) {
      e.renderBlock(128);
      for (int i = 0; i < 128 && g < n; i++) out[g++] = (float) (e.masterBuffer[i].l / 2.147483648e9);
    }
    return out;
  }

  static int loud(float[] x) {
    int b = 0;
    double br = -1;
    for (int o = 0; o + SR < x.length; o += SR / 4) {
      double q = 0;
      for (int i = o; i < o + SR; i++) q += x[i] * x[i];
      if (q > br) {
        br = q;
        b = o;
      }
    }
    return b;
  }
}
