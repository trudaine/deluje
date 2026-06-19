package org.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;

/**
 * Compares our offline renders of the hardware-fidelity test songs against REAL Deluge recordings
 * (resampled on-device, HARDWARE_FIDELITY.md Option B). The reference recordings are committed
 * under {@code src/test/resources/fidelity/hardware-recordings/<Song>/output_000.wav}, so this runs
 * by default; point it elsewhere with {@code -Dhardware.recordings.dir=/path/to/dir}.
 *
 * <p>It ASSERTS only the basics (recording present, both sides non-silent); the printed
 * ComparisonReport is the deliverable for fidelity calibration. Per-song recordings that are absent
 * are skipped (assumption), so partial sets work.
 */
public class HardwareFidelityComparisonTest {

  private static final String DEFAULT_DIR = "src/test/resources/fidelity/hardware-recordings";

  private void renderAndCompare(String songName, double seconds) throws Exception {
    File recordingsDir = new File(System.getProperty("hardware.recordings.dir", DEFAULT_DIR));
    File recorded = new File(recordingsDir, songName + "/output_000.wav");
    // Partial recording sets are fine — only compare what has been recorded.
    org.junit.jupiter.api.Assumptions.assumeTrue(
        recorded.exists(), "no hardware recording yet: " + recorded);

    File songXml = new File("src/main/resources/SONGS/" + songName + ".xml");
    if (!songXml.exists()) {
      songXml = new File("src/main/resources/SONGS/" + songName + ".XML");
    }
    assertTrue(songXml.exists(), "song XML missing: " + songXml);

    File rendered = File.createTempFile("rendered_" + songName, ".wav");
    rendered.deleteOnExit();
    FidelityTestRunner.renderSongToWav(songXml, rendered, seconds);

    FidelityComparisonTool.ComparisonReport r = FidelityComparisonTool.compare(rendered, recorded);

    System.out.println("=== " + songName + " ===");
    System.out.printf(
        "  lag=%d samples (%.2f ms)  corr=%.2f%%  rmsRendered=%.6f  rmsRecorded=%.6f  rmse=%.6f%n",
        r.bestLagSamples,
        r.bestLagMs,
        r.peakCorrelation * 100.0,
        r.rmsRendered,
        r.rmsRecorded,
        r.rmse);

    assertTrue(r.rmsRendered > 1e-4, songName + ": our render is silent");
    assertTrue(r.rmsRecorded > 1e-4, songName + ": hardware recording is silent");
  }

  @Test
  void synth() throws Exception {
    renderAndCompare("TestSynthFidelity", 3.0);
  }

  @Test
  void unison() throws Exception {
    renderAndCompare("TestUnisonFidelity", 3.0);
  }

  @Test
  void kit() throws Exception {
    renderAndCompare("TestKitFidelity", 3.0);
  }

  @Test
  void song006668() throws Exception {
    renderAndCompare("SONG006668", 4.0);
  }

  // ── Batch 2: isolated-subsystem calibration songs ──

  @Test
  void envelope() throws Exception {
    renderAndCompare("TestEnvFidelity", 4.5);
  }

  @Test
  void filter() throws Exception {
    renderAndCompare("TestFilterFidelity", 3.0);
  }

  @Test
  void lfo() throws Exception {
    renderAndCompare("TestLfoFidelity", 3.0);
  }

  @Test
  void fm() throws Exception {
    renderAndCompare("TestFmFidelity", 3.0);
  }

  @Test
  void tuning() throws Exception {
    renderAndCompare("TestTuningFidelity", 3.5);
  }

  @Test
  void noise() throws Exception {
    renderAndCompare("TestNoiseFidelity", 3.0);
  }

  @Test
  void delay() throws Exception {
    renderAndCompare("TestDelayFidelity", 4.5);
  }
}
