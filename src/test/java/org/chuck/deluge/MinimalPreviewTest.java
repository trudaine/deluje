package org.chuck.deluge;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.chuck.audio.util.SndBuf;
import org.junit.jupiter.api.Test;

/** Minimal test: direct SndBuf operations with inline sample data. */
public class MinimalPreviewTest {

  @Test
  public void testSndBufDirectCompute() throws Exception {
    // Pure unit test — no shred, no event, just direct compute
    SndBuf buf = new SndBuf();
    buf.setSamples(new float[] {0.5f, -0.3f, 0.2f, -0.1f, 0.05f});
    buf.rate(1);

    System.out.println("[test] samples loaded: " + buf.samples());
    float out0 = buf.tick(0);
    System.out.println("[test] tick(0): " + out0);
    float out1 = buf.tick(1);
    System.out.println("[test] tick(1): " + out1);
    float out2 = buf.tick(2);
    System.out.println("[test] tick(2): " + out2);
    float out3 = buf.tick(3);
    System.out.println("[test] tick(3): " + out3);
    float out4 = buf.tick(4);
    System.out.println("[test] tick(4): " + out4);
    float out5 = buf.tick(5);
    System.out.println("[test] tick(5): " + out5); // should be 0

    assertTrue(Math.abs(out0 - 0.5f) < 0.001f, "First sample should be 0.5");
    assertTrue(Math.abs(out1 - (-0.3f)) < 0.001f, "Second sample should be -0.3");
    assertTrue(out5 == 0.0f, "Past end should be 0");
  }
}
