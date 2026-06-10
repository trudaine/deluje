package org.chuck.deluge.engine.dsp;

import org.chuck.audio.ChuckUGen;
import org.chuck.deluge.firmware.util.Q31;
import org.chuck.deluge.firmware2.Compressor;

/**
 * Wrapper for the faithful firmware2 Compressor (RMS feedback). Mono version for the DSL. firmware2
 * corrects real bugs the old firmware/ compressor had (float-buffer precision, the 2x tanH/interp2d
 * scale, the persistent anti-alias state), so the response changes — faithful-to-C.
 */
public class FirmwareCompressor extends ChuckUGen {
  private final Compressor firmware = new Compressor();
  private final int[][] buffer = new int[1][2];

  public void setThreshold(float t) {
    // Map 0-1 float to Q31 for firmware
    firmware.setThreshold((int) (t * 2147483647.0));
  }

  public void setRatio(float r) {
    firmware.setRatio((int) (r * 2147483647.0));
  }

  public void setAttack(float a) {
    firmware.setAttack((int) (a * 2147483647.0));
  }

  public void setRelease(float r) {
    firmware.setRelease((int) (r * 2147483647.0));
  }

  /** Set attack time in milliseconds, mapping back to firmware-parity knob position. */
  public void setAttackMS(float ms) {
    // Inverse of: attackMS = 0.5 + (exp(2*knob) - 1) * 10
    double knob = Math.log(Math.max(1e-5, (ms - 0.5) / 10.0 + 1.0)) / 2.0;
    firmware.setAttack(Q31.fromFloat((float) knob));
  }

  /** Set release time in milliseconds, mapping back to firmware-parity knob position. */
  public void setReleaseMS(float ms) {
    // Inverse of: releaseMS = 50 + (exp(2*knob) - 1) * 50
    double knob = Math.log(Math.max(1e-5, (ms - 50.0) / 50.0 + 1.0)) / 2.0;
    firmware.setRelease(Q31.fromFloat((float) knob));
  }

  @Override
  protected float compute(float input, long systemTime) {
    // Bit-accurate conversion with clamping
    buffer[0][0] = Q31.fromFloat(input);
    buffer[0][1] = buffer[0][0];

    // ── Bit-Accurate Hardware Constants ──
    // Hardware unity gain for volAdjust is 1 << 27; neutral finalVolume approx 1 << 25.
    firmware.render(buffer, 1, 1 << 27, 1 << 27, 1 << 25);

    return Q31.toFloat(buffer[0][0]);
  }
}
