package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.*;

import java.io.File;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfSystemProperty;

/**
 * Compares our offline renders of the hardware-fidelity test songs against REAL Deluge recordings
 * (resampled on-device, HARDWARE_FIDELITY.md Option B). Runs only when the recordings directory is
 * supplied: {@code mvn -pl deluge test -Dtest=HardwareFidelityComparisonTest
 * -Dhardware.recordings.dir=/path/to/dir} — the dir must contain one folder per song (e.g. {@code
 * TestSynthFidelity/output_000.wav}).
 *
 * <p>This test only ASSERTS the basics (recording found, both sides non-silent, alignment found);
 * the printed ComparisonReport is the actual deliverable for fidelity calibration.
 */
@EnabledIfSystemProperty(named = "hardware.recordings.dir", matches = ".+")
public class HardwareFidelityComparisonTest {

  private void renderAndCompare(String songName, double seconds) throws Exception {
    File recordingsDir = new File(System.getProperty("hardware.recordings.dir"));
    File recorded = new File(recordingsDir, songName + "/output_000.wav");
    assertTrue(recorded.exists(), "hardware recording missing: " + recorded);

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
}
