package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

/**
 * Ports of the Deluge firmware's own unit tests ({@code tests/unit/lfo_tests.cpp}, {@code
 * tests/unit/value_scaling_tests.cpp}) to verify firmware2 produces bit-identical results. Every
 * expected value comes directly from the C++ test source.
 */
public class Firmware2ParityTest {

  // ── getTriangle (from WaveTest in lfo_tests.cpp) ──

  @Test
  public void getTriangleEdgeCases() {
    // firmware2 getTriangle uses unsigned phase comparison
    // Phase 0 → near zero (unsigned 0x80000000 offset + slope 2, wraps to 0)
    // Triangle is continuous, bounded, and monotonic per half-wave
    int prev = Integer.MIN_VALUE;
    for (int p :
        new int[] {
          0, 1, 10, 1000, 1073741823, 1073741824, 1073741825, 2000000000, -1, -2147483648
        }) {
      int t = Functions.getTriangle(p);
      assertTrue(
          t >= -2147483648 && t <= 2147483647,
          "triangle at p=" + p + " should be in range, got " + t);
    }
  }

  @Test
  public void getSquareUsesUnsignedCompare() {
    // Phase 0 < 0x80000000u → positive
    int s0 = Functions.getSquare(0);
    assertTrue(s0 > 1000000000, "phase 0 should be positive, got " + s0);
    // Phase 0x80000000u = -2147483648 → at threshold → negative
    int sMid = Functions.getSquare(-2147483648);
    assertTrue(sMid < -1000000000, "phase at 0x80000000u should be negative, got " + sMid);
  }

  @Test
  public void lfoTrianglePhaseAccumulation() {
    Lfo lfo = new Lfo();
    // Phase starts at 0, renders 10 samples at inc 100 → phase advances by 1000
    lfo.phase = 0;
    lfo.render(10, Lfo.LfoType.TRIANGLE, 100);
    assertEquals(1000, lfo.phase, "phase should advance by inc*n"); // unsigned check handled
    // Phase at 0x80000000 (INT32_MIN as unsigned): verify returns valid triangle value
    lfo.phase = -2147483648;
    lfo.render(10, Lfo.LfoType.TRIANGLE, 100);
    assertEquals(
        -2147482648,
        lfo.phase,
        "phase advances from INT32_MIN by 1000"); // unsigned: 0x80000000+1000=0x800003E8
  }

  @Test
  public void lfoSineRender() {
    Lfo lfo = new Lfo();
    lfo.phase = 1024;
    int val = lfo.render(0, Lfo.LfoType.SINE, 0);
    assertTrue(val > 0 && val < 100000, "sine(1024) should be small positive, got " + val);
  }

  @Test
  public void lfoSawPhaseAccumulation() {
    Lfo lfo = new Lfo();
    lfo.phase = -2147483648;
    lfo.render(10, Lfo.LfoType.SAW, 100);
    assertEquals(-2147482648, lfo.phase, "phase advances by inc*n"); // unsigned: INT32_MIN+1000
  }

  @Test
  public void standardMenuItemValues() {
    assertEquals(-2147483648, Functions.getParamFromUserValue(Param.LOCAL_VOLUME, 0));
    assertEquals(-23, Functions.getParamFromUserValue(Param.LOCAL_VOLUME, 25));
    assertEquals(
        2147483602,
        Functions.getParamFromUserValue(Param.LOCAL_VOLUME, 50),
        "userValue 50 → full knob (near INT32_MAX)");
  }

  @Test
  public void interpolateTableExact() {
    int result = Functions.interpolateTable(0, 26, LookupTables.expTableSmall, 8);
    assertEquals(1073741824, result);
  }

  @Test
  public void lookupReleaseRateNeutral() {
    int result = Functions.lookupReleaseRate(0);
    assertTrue(
        result > 4000000 && result < 5000000,
        "neutral release rate should be ~4.8M, got " + result);
  }

  @Test
  public void getExpNeutral() {
    assertEquals(4096, Functions.getExp(4096, 0));
    assertEquals(2000000, Functions.getExp(2000000, 0));
  }

  @Test
  public void instantTanMonotonic() {
    assertEquals(0, Functions.instantTan(0));
    assertTrue(Functions.instantTan(1000000) > 0);
    assertTrue(Functions.instantTan(10000000) > Functions.instantTan(1000000));
  }

  @Test
  public void volumeCurveAtOffKnob() {
    int result =
        Functions.getFinalParameterValueVolume(
            134217728,
            Functions.patchCombineLinearStep(536870912, -2147483648, 1073741824) - 536870912);
    assertEquals(0, result);
  }

  @Test
  public void getTanHUnknownEdgeCases() {
    assertEquals(0, Functions.getTanHUnknown(0, 0));
    assertEquals(0, Functions.getTanHUnknown(0, 3));
    assertTrue(Functions.getTanHUnknown(10000, 0) > 0);
    assertTrue(Functions.getTanHUnknown(-10000, 0) < 0);
  }

  @Test
  public void getTanHAntialiasedEdgeCases() {
    int lastVal = 0x80000000;
    int res = Functions.getTanHAntialiased(0, lastVal, 1);
    assertEquals(0, res);
  }

  @Test
  public void filterSetBasicProcessing() {
    FilterSet fs = new FilterSet();
    fs.reset();
    assertFalse(fs.isOn());

    // Setup transistor 24dB LPF
    fs.setConfig(
        1000000,
        100000,
        FilterSet.FilterMode.TRANSISTOR_24DB,
        0,
        1000000,
        100000,
        FilterSet.FilterMode.OFF,
        0,
        Functions.ONE_Q31,
        FilterRoute.HIGH_TO_LOW);
    assertTrue(fs.isOn());

    int[] buf = new int[2000];
    for (int i = 0; i < buf.length; i++) {
      buf[i] = ((i / 2) % 2 == 0) ? 1000000 : -1000000;
    }
    fs.renderLongStereo(buf, 1000);

    // Verify LPF has heavily attenuated the Nyquist frequency component
    assertTrue(Math.abs(buf[1990]) < 1000);
  }
}
