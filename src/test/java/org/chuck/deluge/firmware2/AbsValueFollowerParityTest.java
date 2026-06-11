package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class AbsValueFollowerParityTest {

  @Test
  public void testKnobMapping() {
    AbsValueFollower follower = new AbsValueFollower();

    // 1. Minimum knob positions (0)
    int attackMS = follower.setAttack(0);
    int releaseMS = follower.setRelease(0);
    assertEquals(0, attackMS, "Min attackMS should truncate to 0 (0.5f)");
    assertEquals(50, releaseMS, "Min releaseMS should be 50");

    // 2. Maximum knob positions (Integer.MAX_VALUE)
    attackMS = follower.setAttack(Integer.MAX_VALUE);
    releaseMS = follower.setRelease(Integer.MAX_VALUE);
    // std::exp(2 * 1.0) = ~7.389
    // attackMS = 0.5f + (7.389f - 1.0f) * 10.0f = 0.5f + 63.89f = 64.39f -> 64
    // releaseMS = 50.0f + (7.389f - 1.0f) * 50.0f = 50.0f + 319.45f = 369.45f -> 369
    assertEquals(64, attackMS);
    assertEquals(369, releaseMS);
  }

  @Test
  public void testEnvelopeTracking() {
    AbsValueFollower follower = new AbsValueFollower();
    follower.setup(0, 0); // Fast attack/release settings
    follower.reset();

    // Feed a constant high amplitude signal block (amplitude = 1000000)
    int[][] buffer = new int[128][2];
    for (int i = 0; i < 128; i++) {
      buffer[i][0] = 1000000;
      buffer[i][1] = 1000000;
    }

    float[] rmsValues = follower.calcApproxRMS(buffer);

    // RMS outputs are in log space: log(lastMeanL + 1e-24f)
    // The tracked mean level should rise towards 1000000.
    // log(1000000) is ~13.815.
    // Let's assert that the envelope starts tracking and goes positive.
    assertTrue(rmsValues[0] > 0.0f, "Log mean L should be non-zero positive");
    assertTrue(rmsValues[1] > 0.0f, "Log mean R should be non-zero positive");
    float lastLogVal = rmsValues[0];

    // Feed another block of high amplitude
    rmsValues = follower.calcApproxRMS(buffer);
    assertTrue(rmsValues[0] > lastLogVal, "Envelope should rise on attack");
    lastLogVal = rmsValues[0];

    // Feed a block of zero amplitude (release stage should kick in)
    int[][] silentBuffer = new int[128][2];
    rmsValues = follower.calcApproxRMS(silentBuffer);
    assertTrue(rmsValues[0] < lastLogVal, "Envelope should drop on release");
  }
}
