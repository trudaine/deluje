package org.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.deluge.firmware2.Functions;
import org.junit.jupiter.api.Test;

/**
 * Faithful envelope rate curves (#6): port of lookupReleaseRate + the
 * getFinalParameterValueExpWithDumbEnvelopeHack stages. Attack uses getExp on the negated knob;
 * decay/release use the release-rate table scaled by the neutral. Pinned to hand-traced firmware
 * values, plus the knob-direction (attack: higher knob -> faster; decay: higher knob -> slower).
 */
public class FirmwareEnvRateTest {

  private static int finalEnvRateParam(int paramNeutralValue, int patchedValue, int type) {
    int paramId;
    if (type == 0) {
      paramId = org.deluge.firmware2.Param.LOCAL_ENV_0_ATTACK;
    } else if (type == 1) {
      paramId = org.deluge.firmware2.Param.LOCAL_ENV_0_DECAY;
    } else {
      paramId = org.deluge.firmware2.Param.LOCAL_ENV_0_RELEASE;
    }
    return Functions.getFinalParameterValueExpWithDumbEnvelopeHack(
        paramNeutralValue, patchedValue, paramId);
  }

  @Test
  public void lookupReleaseRateNeutral() {
    // input 0 -> whichValue 32, interpolation weight 0 -> ~releaseRateTable64[32] (4792960).
    assertEquals(4792958, Functions.lookupReleaseRate(0));
  }

  @Test
  public void neutralIncrementsMatchFirmware() {
    // Attack neutral 4096, combo 0 -> getExp(4096,0) = 4096.
    assertEquals(4096, finalEnvRateParam(4096, 0, 0));
    // Decay neutral 70<<9, combo 0 -> mult(35840, 4792958) = 39.
    assertEquals(39, finalEnvRateParam(70 << 9, 0, 1));
    // Release neutral 140<<9, combo 0 -> mult(71680, 4792958) = 79 (~twice decay).
    assertEquals(79, finalEnvRateParam(140 << 9, 0, 2));
  }

  @Test
  public void knobDirections() {
    // Attack: increment via getExp(4096, -combo). Higher combo -> smaller increment -> slower
    // attack.
    int aLow = finalEnvRateParam(4096, -200000000, 0); // negated -> large -> fast
    int aHigh = finalEnvRateParam(4096, 200000000, 0); // negated -> small -> slow
    assertTrue(aLow > aHigh, "attack: -combo faster than +combo (" + aLow + " vs " + aHigh + ")");
    // Decay: mult(neutral, lookupReleaseRate(combo)). releaseRateTable decreases with index, so a
    // higher combo -> smaller increment -> slower (longer) decay.
    int dLow = finalEnvRateParam(70 << 9, -200000000, 1);
    int dHigh = finalEnvRateParam(70 << 9, 200000000, 1);
    assertTrue(dLow > dHigh, "decay: lower combo = faster decay (" + dLow + " vs " + dHigh + ")");
  }
}
