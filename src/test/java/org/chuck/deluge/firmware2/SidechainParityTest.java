package org.chuck.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class SidechainParityTest {

  @Test
  public void testSidechainAttackReleaseFlow() {
    Sidechain sc = new Sidechain();
    sc.syncLevel = 0; // Sync off

    // Set attack to a non-zero rate, release to a normal rate
    sc.attack = 100000;
    sc.release = 50000;

    // Trigger hit with full strength
    sc.registerHit(Integer.MAX_VALUE);

    // Render first block (should enter ATTACK stage)
    int duckingAmount = sc.render(10, 0); // Render 10 samples
    assertEquals(Envelope.Stage.ATTACK, sc.status);

    // Ducking amount should be negative (lastValue - ONE_Q31)
    assertTrue(duckingAmount < 0, "Ducking should be active (negative)");
    int initialDip = duckingAmount;

    // Render more samples to cross the attack threshold (8388608)
    // Attack rate is 100000. Under getActualAttackRate with syncLevel=0, it is exactly 100000.
    // To reach 8388608, it takes 8388608 / 100000 = ~84 samples.
    // Let's render 100 samples total.
    duckingAmount = sc.render(90, 0);

    // It should have transitioned to RELEASE stage
    assertEquals(Envelope.Stage.RELEASE, sc.status);

    // Render a lot of samples (say 2000) to let it release back to OFF
    duckingAmount = sc.render(2000, 0);
    assertEquals(Envelope.Stage.OFF, sc.status);
    assertEquals(0, duckingAmount, "Should release back to silence (0 ducking)");
  }

  @Test
  public void testRetrospectiveHit() {
    Sidechain sc = new Sidechain();
    sc.syncLevel = 0;
    sc.attack = 100000;
    sc.release = 50000;

    // Register a hit retrospectively (happened 50 samples ago)
    sc.registerHitRetrospectively(Integer.MAX_VALUE, 50);

    // Since 50 samples ago is less than the attack length (~84 samples), it should be in ATTACK
    // stage
    assertEquals(Envelope.Stage.ATTACK, sc.status);
    assertTrue(sc.pos > 0, "Position should be advanced");

    // Register a hit that happened 200 samples ago (should be in RELEASE stage)
    sc.registerHitRetrospectively(Integer.MAX_VALUE, 200);
    assertEquals(Envelope.Stage.RELEASE, sc.status);
  }

  @Test
  public void testCombineHitStrengths() {
    // combineHitStrengths uses half of max + capped sum >> 1
    int c1 = Sidechain.combineHitStrengths(1000, 2000);
    int c2 = Sidechain.combineHitStrengths(2000, 1000);
    assertEquals(c1, c2, "Combination should be commutative");
    assertTrue(c1 > 2000, "Combined strength should exceed individual max");
  }
}
