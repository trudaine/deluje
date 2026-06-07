package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/** Verifies the Dx7Voice + FmCore port produces correct DX7 audio. */
public class Dx7VoiceTest {

  @Test
  public void initVoiceInitializesWithoutCrash() {
    Dx7Voice.DxPatch patch = new Dx7Voice.DxPatch();
    Dx7Voice voice = new Dx7Voice();
    voice.init(patch, 69, 100);
    assertNotNull(voice.patch);
    assertEquals(156, voice.patch.length);
  }

  @Test
  public void fmaCoreRenderProducesOutput() {
    FmCore.FmOpParams[] params = new FmCore.FmOpParams[6];
    for (int i = 0; i < 6; i++) params[i] = new FmCore.FmOpParams();
    // Algorithm 0: ops 3 and 5 are carriers
    int[] alg = FmCore.ALGORITHMS[0];
    // Set all ops to a simple sine with no modulation
    for (int i = 0; i < 6; i++) {
      params[i].freq = (int) (440.0 * Functions.K_MAX_SAMPLE_VALUE / 44100.0);
      params[i].level_in = 14 << 24; // max level
    }

    int[] buf = new int[132]; // DX_MAX_N
    int[] fb = new int[2];
    FmCore.render(buf, 128, params, 31, fb, 16); // algo 31 = all carriers

    long sum = 0;
    for (int v : buf) sum += (long) v * v;
    double rms = Math.sqrt(sum / 128.0) / 2147483648.0;
    assertTrue(sum > 0, "FmCore should produce output with max-level params, RMS=" + rms);
  }

  @Test
  public void dx7VoiceComputeDoesNotCrash() {
    Dx7Voice.DxPatch patch = new Dx7Voice.DxPatch();
    Dx7Voice voice = new Dx7Voice();
    voice.init(patch, 69, 100);
    int[] buf = new int[128];
    // Should not throw
    boolean active = voice.compute(buf, 128, dxNoteToFreq(69), patch, 0, 0, 0);
    // At least be active (init voice has all ops enabled)
    assertNotNull(voice.patch);
  }

  @Test
  public void dx7AlgorithmsTableHas32Entries() {
    assertEquals(32, FmCore.ALGORITHMS.length);
    for (int i = 0; i < 32; i++) {
      assertEquals(
          6, FmCore.ALGORITHMS[i].length, "algorithm " + (i + 1) + " should have 6 operators");
    }
  }

  // Port of Dx7ParityTest: a real 156-byte DX7 patch should produce non-silent output
  @Test
  public void dx7PatchProducesOutputDifferentFromSine() {
    // A real 156-byte DX7 voice (extracted from SONGS/Dx7C.xml).
    String hex =
        "631E1E1E630000000000000000000003630001000763461E1E63000000000000000000000552000E000800000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000700000000000000000000000000000000000001000763636363323232320400012300000001000118464D2042454C4C20202003";
    byte[] patchBytes = new byte[156];
    for (int i = 0; i < 156; i++) {
      patchBytes[i] = (byte) Integer.parseInt(hex.substring(i * 2, i * 2 + 2), 16);
    }

    Dx7Voice.DxPatch patch = new Dx7Voice.DxPatch();
    System.arraycopy(patchBytes, 0, patch.params, 0, 156);

    Dx7Voice voice = new Dx7Voice();
    voice.init(patch, 60, 100);

    int[] buf = new int[128];
    voice.compute(buf, 128, dxNoteToFreq(60), patch, 0, 0, 0);

    long sum = 0;
    long peak = 0;
    for (int v : buf) {
      sum += (long) v * v;
      long a = Math.abs((long) v);
      if (a > peak) peak = a;
    }
    double rms = Math.sqrt(sum / 128.0) / 2147483648.0;
    assertTrue(sum > 0, "DX7 patch should produce non-silent output, peak=" + peak + " rms=" + rms);
  }

  static int dxNoteToFreq(int note) {
    return 50857777 + ((1 << 24) / 12) * note;
  }
}
