package org.chuck.deluge.engine.dsp;

import static org.junit.jupiter.api.Assertions.*;

import org.junit.jupiter.api.Test;

public class NativeAdsrTest {

  @Test
  public void testLifecycle() {
    NativeAdsr adsr = new NativeAdsr(44100);
    assertFalse(adsr.isActive());

    adsr.keyOn();
    assertTrue(adsr.isActive());

    // Attack phase
    float first = adsr.tick();
    assertTrue(first >= 0);

    // Run until decay
    for (int i = 0; i < 4410; i++) adsr.tick(); // ~100ms

    adsr.keyOff();
    // Should eventually become inactive
    for (int i = 0; i < 44100; i++) adsr.tick();
    assertFalse(adsr.isActive());
  }

  @Test
  public void testFastRelease() {
    NativeAdsr adsr = new NativeAdsr(44100);
    adsr.keyOn();
    adsr.tick();
    assertTrue(adsr.isActive());

    adsr.fastRelease();
    // Should become inactive quickly (within 10ms = 441 samples)
    for (int i = 0; i < 500; i++) adsr.tick();
    assertFalse(adsr.isActive());
  }
}
