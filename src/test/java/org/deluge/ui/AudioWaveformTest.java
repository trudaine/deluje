package org.deluge.ui;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

/** Pins the audio-view waveform envelope decimation extracted from the renderer. */
class AudioWaveformTest {

  @Test
  void fixedOutputSize_regardlessOfInputLength() {
    assertEquals(256, AudioWaveform.envelope(new float[10], 256).length);
    assertEquals(256, AudioWaveform.envelope(new float[1_000_000], 256).length);
    assertEquals(64, AudioWaveform.envelope(new float[500], 64).length);
  }

  @Test
  void nullOrEmpty_isAllZero() {
    for (float v : AudioWaveform.envelope(null, 32)) assertEquals(0f, v);
    for (float v : AudioWaveform.envelope(new float[0], 32)) assertEquals(0f, v);
  }

  @Test
  void takesAbsoluteAmplitude() {
    // Constant -0.5 buffer -> envelope should be +0.5 everywhere (abs), after smoothing still 0.5.
    float[] buf = new float[1024];
    java.util.Arrays.fill(buf, -0.5f);
    float[] env = AudioWaveform.envelope(buf, 16);
    for (float v : env) assertEquals(0.5f, v, 1e-6f);
  }

  @Test
  void smoothingAveragesNeighbours_atAnIsolatedSpike() {
    // One loud sample at index 0, silence elsewhere; decimation with step=1 keeps it in point 0.
    float[] buf = new float[16];
    buf[0] = 1.0f;
    float[] env = AudioWaveform.envelope(buf, 16);
    // 5-tap box at edge i=0 averages points {0,1,2} (count=3): 1.0/3.
    assertEquals(1.0f / 3.0f, env[0], 1e-6f);
    // i=2 window {0..4} includes the spike once over 5 taps: 1.0/5.
    assertEquals(1.0f / 5.0f, env[2], 1e-6f);
    // Far from the spike -> zero.
    assertEquals(0f, env[5], 1e-6f);
  }

  @Test
  void decimatesByPointSampling_everyNthSample() {
    // 512 samples, target 256 -> step 2 -> point i samples index 2i. Put a marker at index 4 (->
    // point 2) and confirm it lands there (abs), with neighbours picking up smoothing.
    float[] buf = new float[512];
    buf[4] = 0.8f;
    float[] env = AudioWaveform.envelope(buf, 256);
    assertTrue(env[2] > 0f, "the sampled spike at index 4 shows up around point 2");
  }
}
