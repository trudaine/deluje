package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Faithful-port checks for {@link Metronome} (square-wave click). */
class MetronomeTest {

  @Test
  void setVolumeMatchesC() {
    Metronome m = new Metronome();
    for (int lp = 0; lp <= 50; lp++) {
      int expected =
          (int) ((Math.exp((double) ((float) lp / 200.0f)) - 1.0) * (double) (float) (1 << 27));
      m.setVolume(lp);
      assertEquals(expected, m.metronomeVolume, "setVolume(" + lp + ")");
    }
  }

  @Test
  void renderIsSquareWaveAndDecays() {
    Metronome m = new Metronome();
    m.setVolume(25);
    int vol = Metronome.ONE_Q31;
    int high = Functions.multiply_32x32_rshift32(m.metronomeVolume, vol);

    // phaseIncrement so the buffer crosses the ONE_Q31 boundary partway through.
    int phaseInc = 1 << 26; // 64 samples to wrap 2^32; high for first 32, low for next 32
    m.trigger(phaseInc);

    int n = 64;
    int[][] buf = new int[n][2];
    m.render(buf, n, vol);

    // Re-derive the square wave + accumulation.
    int phase = 0;
    for (int i = 0; i < n; i++) {
      int value = (Integer.compareUnsigned(phase, Metronome.ONE_Q31) <= 0) ? high : -high;
      phase += phaseInc;
      assertEquals(value, buf[i][0], "L i=" + i);
      assertEquals(value, buf[i][1], "R i=" + i);
    }
    assertTrue(m.sounding, "still sounding after 64 samples");

    // After > 1000 total samples it stops.
    for (int blk = 0; blk < 20; blk++) {
      m.render(new int[64][2], 64, vol);
    }
    assertFalse(m.sounding, "stops after ~1000 samples");

    // Not sounding ⇒ render leaves the buffer untouched.
    int[][] silent = new int[8][2];
    m.render(silent, 8, vol);
    for (int[] s : silent) {
      assertEquals(0, s[0]);
      assertEquals(0, s[1]);
    }
  }
}
