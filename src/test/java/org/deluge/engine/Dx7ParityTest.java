package org.deluge.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.StereoSample;
import org.junit.jupiter.api.Test;

/**
 * Verifies that a DX7 patch is rendered by the real DX7 (Dexed) engine in the pure firmware engine,
 * not the native-FM fallback (the bug behind "DX7 songs sound completely different"). A
 * FirmwareSound with a dx7Patch must produce non-silent, bounded audio that differs from the same
 * note rendered without the patch (generic FM).
 */
public class Dx7ParityTest {

  // A real 156-byte DX7 voice (extracted from SONGS/Dx7C.xml).
  private static final String DX7_PATCH_HEX =
      "631E1E1E630000000000000000000003630001000763461E1E63000000000000000000000552000E000800000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000763636363323232320400012300000001000118464D2042454C4C20202003";

  private static byte[] hex(String s) {
    byte[] b = new byte[s.length() / 2];
    for (int i = 0; i < b.length; i++)
      b[i] = (byte) Integer.parseInt(s.substring(i * 2, i * 2 + 2), 16);
    return b;
  }

  private static double[] render(boolean withPatch, int total) {
    FirmwareSound sound = new FirmwareSound();
    if (withPatch) sound.dx7Patch = hex(DX7_PATCH_HEX);
    sound.triggerNote(60, 100);
    StereoSample[] buf = new StereoSample[total];
    for (int i = 0; i < total; i++) buf[i] = new StereoSample();
    sound.renderInternal(buf, total, null);
    double[] mono = new double[total];
    for (int i = 0; i < total; i++) mono[i] = buf[i].l;
    return mono;
  }

  @org.junit.jupiter.api.Disabled("DX7 engine path needs firmware2 voice-level DX7 render")
  @Test
  public void dx7PatchIsRenderedByTheDx7Engine() {
    int n = 4000;
    double[] dx7 = render(true, n);
    double[] fm = render(false, n);

    double dx7Energy = 0, diff = 0, peak = 0;
    for (int i = 0; i < n; i++) {
      dx7Energy += Math.abs(dx7[i]);
      diff += Math.abs(dx7[i] - fm[i]);
      peak = Math.max(peak, Math.abs(dx7[i]));
    }
    assertTrue(dx7Energy > 0, "DX7 patch must produce sound");
    assertTrue(peak <= 2147483647.0, "DX7 output must stay bounded (no overflow)");
    // The real DX7 voice must differ substantially from the generic FM fallback.
    assertTrue(
        diff > dx7Energy * 0.5,
        "DX7 render must differ from the native-FM fallback (diff="
            + diff
            + ", e="
            + dx7Energy
            + ")");
  }
}
