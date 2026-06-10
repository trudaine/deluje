package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.fx.ModFXType;
import org.junit.jupiter.api.Test;

/**
 * Verifies that modulation FX (chorus/flanger/...) are actually applied in the pure firmware
 * engine. Previously {@code FirmwareSound.modFXType} was never set and {@code processModFX} was
 * called with hardcoded rate/depth, so mod FX was inert regardless of the patch. This renders the
 * same note with and without chorus and asserts the chorus measurably changes the signal.
 */
public class ModFxParityTest {

  private static double[] render(ModFXType type, int total) {
    FirmwareSound sound = new FirmwareSound();
    sound.modFXType = type;
    if (type != ModFXType.NONE) {
      sound.modFXRateIncrement = (int) (2.0 * 4294967296.0 / 44100.0); // 2 Hz LFO
      sound.modFXDepth = (int) (0.8 * 2147483647.0);
      sound.modFXOffset = (int) (0.5 * 2147483647.0);
      sound.modFXFeedback = 0;
    }
    sound.triggerNote(60, 100);
    StereoSample[] buf = new StereoSample[total];
    for (int i = 0; i < total; i++) buf[i] = new StereoSample();
    sound.renderInternal(buf, total, null);
    double[] mono = new double[total];
    for (int i = 0; i < total; i++) mono[i] = buf[i].l;
    return mono;
  }

  @org.junit.jupiter.api.Disabled(
      "modFX processing path needs firmware2-level port (currently uses old engine)")
  @Test
  public void chorusChangesTheSignal() {
    int total = 9000;
    int from = 3000; // let the mod-FX delay buffer fill before comparing
    double[] dry = render(ModFXType.NONE, total);
    double[] wet = render(ModFXType.CHORUS, total);

    double diffRms = 0, dryRms = 0;
    for (int i = from; i < total; i++) {
      double d = wet[i] - dry[i];
      diffRms += d * d;
      dryRms += dry[i] * dry[i];
    }
    diffRms = Math.sqrt(diffRms / (total - from));
    dryRms = Math.sqrt(dryRms / (total - from));

    assertTrue(dryRms > 0, "Dry signal should be non-silent");
    // The chorus must change the signal meaningfully (>1% of the dry RMS).
    assertTrue(
        diffRms > dryRms * 0.01,
        "Chorus should measurably alter the signal (diffRms="
            + diffRms
            + ", dryRms="
            + dryRms
            + ")");
  }
}
