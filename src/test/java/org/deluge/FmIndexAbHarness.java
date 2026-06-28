package org.deluge;

import java.io.File;
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
 * FM-index calibration A/B harness. For each native-FM patch with a clean hardware reference, it
 * renders at a sweep of modulation-index multipliers (via {@link Voice#testFmIndexScale}), scores
 * each against the reference (log-bin spectral cosine), and writes a WAV per multiplier to {@code
 * $TMPDIR/deluge-fm-ab/} so you can A/B by ear. The multiplier with the highest cosine is the
 * empirical index calibration; 1.0 winning means our FM index is already faithful.
 *
 * <p>Run: {@code mvn test -Dtest=FmIndexAbHarness -Dgpg.skip=true}. Only native-FM patches are
 * affected (DX7 routes through FmCore and would need its own hook).
 */
public class FmIndexAbHarness {
  static final int SR = 44100;
  static final double[] SCALES = {0.25, 0.5, 0.75, 1.0, 1.5, 2.0};

  // patch XML, hardware reference WAV, MIDI note. ONLY native-format FM presets (real
  // <modulator1Amount> etc.) are valid here: the 103/117 "_C5" fixtures use the non-native
  // mode="fm" fmRatio/fmAmount attributes, which the hardware can't read — it falls back to the
  // default modulator amount (INT_MIN = FM off), so those recordings are a near-pure carrier and
  // can't validate our FM-on render (see FIDELITY_GAP_ANALYSIS 4.1quater). Add native ludocard FM
  // presets + their re-recorded references here to calibrate FM index against real hardware.
  static final String[][] CASES = {
    {"src/test/resources/fidelity/049 Basic FM.XML", "/fidelity/REC00010.WAV", "60"},
  };

  @Test
  @Tag("slow")
  void sweep() throws Exception {
    File outDir = new File(System.getProperty("java.io.tmpdir"), "deluge-fm-ab");
    outDir.mkdirs();
    boolean any = false;
    System.out.printf("%nFM-index A/B harness — WAVs in %s%n", outDir.getAbsolutePath());
    for (String[] c : CASES) {
      File xml = new File(c[0]);
      java.net.URL ref = getClass().getResource(c[1]);
      if (!xml.isFile() || ref == null) {
        System.out.printf("  [skip] %s (missing patch or reference)%n", c[0]);
        continue;
      }
      any = true;
      float[] hw = FidelityScorecardTest.readWavMono(new File(ref.toURI()));
      int note = Integer.parseInt(c[2]);
      String name = xml.getName().replace(".XML", "").replace(".xml", "");
      System.out.printf("%n=== %s (note %d) vs %s ===%n", name, note, c[1]);
      double bestCos = -2;
      double bestScale = 1.0;
      for (double scale : SCALES) {
        float[] our = render(xml, note, scale);
        double cos =
            FidelityScorecardTest.cosine(
                FidelityScorecardTest.spectrum(our, loud(our), SR),
                FidelityScorecardTest.spectrum(hw, loud(hw), SR));
        writeWav(our, new File(outDir, name + "_x" + scale + ".wav"));
        System.out.printf("   index x%.2f  spectral cosine=%.3f%n", scale, cos);
        if (cos > bestCos) {
          bestCos = cos;
          bestScale = scale;
        }
      }
      writeWav(hw, new File(outDir, name + "_HW.wav"));
      System.out.printf(
          "   >>> best index multiplier = x%.2f (cosine %.3f)%s%n",
          bestScale, bestCos, bestScale == 1.0 ? "  [1.0 = faithful]" : "");
    }
    Assumptions.assumeTrue(any, "no FM patch/reference pairs present");
  }

  static float[] render(File xml, int note, double scale) throws Exception {
    FirmwareAudioEngine.cpuDireness = 0;
    org.deluge.firmware2.Functions.resetNoiseSeed();
    Voice.testStartPhaseOverrideOsc1.set(0);
    Voice.testStartPhaseOverrideOsc2.set(0);
    Voice.testFmIndexScale = scale;
    try {
      SynthTrackModel synth = DelugeXmlParser.parseSynth(xml);
      synth.setName("fm");
      ClipModel clip = new ClipModel("c", 1, 16);
      clip.setStep(0, 0, StepData.of(true, 1f, 16f, 1f, note));
      synth.addClip(clip);
      ProjectModel project = new ProjectModel();
      project.addTrack(synth);
      ProjectModel song = FirmwareFactory.createSong(project);
      FirmwareSound fs = (FirmwareSound) song.getTracks().get(0).getActiveClip().getSound();
      fs.triggerNote(note, 100);
      FirmwareAudioEngine e = new FirmwareAudioEngine();
      e.sounds.add(fs);
      int n = SR * 3;
      float[] out = new float[n];
      int g = 0;
      while (g < n) {
        e.renderBlock(128);
        for (int i = 0; i < 128 && g < n; i++)
          out[g++] = (float) (e.masterBuffer[i].l / 2.147483648e9);
      }
      return out;
    } finally {
      Voice.testFmIndexScale = 1.0;
    }
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

  static void writeWav(float[] x, File f) throws Exception {
    byte[] data = new byte[x.length * 2];
    for (int i = 0; i < x.length; i++) {
      int v = Math.max(-32768, Math.min(32767, Math.round(x[i] * 32767f)));
      data[i * 2] = (byte) (v & 0xFF);
      data[i * 2 + 1] = (byte) ((v >> 8) & 0xFF);
    }
    javax.sound.sampled.AudioFormat fmt =
        new javax.sound.sampled.AudioFormat(SR, 16, 1, true, false);
    try (javax.sound.sampled.AudioInputStream ais =
        new javax.sound.sampled.AudioInputStream(
            new java.io.ByteArrayInputStream(data), fmt, x.length)) {
      javax.sound.sampled.AudioSystem.write(ais, javax.sound.sampled.AudioFileFormat.Type.WAVE, f);
    }
  }
}
