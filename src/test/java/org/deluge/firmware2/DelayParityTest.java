package org.deluge.firmware2;

import static org.junit.jupiter.api.Assertions.assertTrue;

import org.junit.jupiter.api.Test;

public class DelayParityTest {

  @Test
  public void testPingPongAlternation() {
    Delay delay = new Delay();
    delay.pingPong = true;
    delay.analog = false;

    Delay.State state = new Delay.State();
    state.doDelay = true;
    state.userDelayRate = 1073741824; // 256 frames delay
    state.delayFeedbackAmount = 500000000; // Large feedback ~0.5 scale

    // Prime the delay
    delay.setupWorkingState(state, 1, true);

    // 1. Render 4 blocks of silence to allow buffer swap to primary
    for (int b = 0; b < 4; b++) {
      int[][] temp = new int[128][2];
      delay.process(temp, 128, state);
    }

    // 2. Feed a single left-channel impulse
    int[][] buffer = new int[128][2];
    buffer[0][0] = 10000000; // Left impulse
    buffer[0][1] = 0;

    delay.process(buffer, 128, state);

    // 3. Render 10 blocks of 128 samples to capture multiple echoes
    int blockCount = 10;
    int[][] outputAccumulator = new int[blockCount * 128][2];

    for (int b = 0; b < blockCount; b++) {
      int[][] tempBuf = new int[128][2];
      delay.process(tempBuf, 128, state);
      for (int i = 0; i < 128; i++) {
        outputAccumulator[b * 128 + i][0] = tempBuf[i][0];
        outputAccumulator[b * 128 + i][1] = tempBuf[i][1];
      }
    }

    // Find the first and second echo peaks in wider windows to support filter delays
    int firstEchoPeakL = 0;
    int firstEchoPeakR = 0;
    int secondEchoPeakL = 0;
    int secondEchoPeakR = 0;

    // First echo window around frame 236
    for (int i = 80; i < 160; i++) {
      firstEchoPeakL = Math.max(firstEchoPeakL, Math.abs(outputAccumulator[i][0]));
      firstEchoPeakR = Math.max(firstEchoPeakR, Math.abs(outputAccumulator[i][1]));
    }

    // Second echo window around frame 472
    for (int i = 320; i < 410; i++) {
      secondEchoPeakL = Math.max(secondEchoPeakL, Math.abs(outputAccumulator[i][0]));
      secondEchoPeakR = Math.max(secondEchoPeakR, Math.abs(outputAccumulator[i][1]));
    }

    // Ping-pong verification:
    // Left impulse -> 1st echo should be on the Right channel.
    // 2nd echo should be on the Left channel.
    assertTrue(
        firstEchoPeakR > firstEchoPeakL * 10,
        "First echo should be primarily on the Right channel. L="
            + firstEchoPeakL
            + ", R="
            + firstEchoPeakR);
    assertTrue(
        secondEchoPeakL > secondEchoPeakR * 10,
        "Second echo should be primarily on the Left channel. L="
            + secondEchoPeakL
            + ", R="
            + secondEchoPeakR);
  }

  @Test
  public void testAnalogSaturationClamping() {
    Delay.State state = new Delay.State();
    state.doDelay = true;
    state.userDelayRate = 1073741824; // 256 frames delay
    state.delayFeedbackAmount = 1000000000; // Almost unity feedback

    // 1. Digital delay (no saturation)
    Delay digitalDelay = new Delay();
    digitalDelay.pingPong = false;
    digitalDelay.analog = false;
    digitalDelay.setupWorkingState(state, 1, true);

    // Prime the digital delay
    for (int b = 0; b < 4; b++) {
      int[][] temp = new int[128][2];
      digitalDelay.process(temp, 128, state);
    }

    int[][] bufDig = new int[128][2];
    bufDig[0][0] = 50000000; // Hot input
    bufDig[0][1] = 50000000;
    digitalDelay.process(bufDig, 128, state);

    // 2. Analog delay (tanh saturation)
    Delay analogDelay = new Delay();
    analogDelay.pingPong = false;
    analogDelay.analog = true;
    analogDelay.setupWorkingState(state, 1, true);

    // Prime the analog delay
    for (int b = 0; b < 4; b++) {
      int[][] temp = new int[128][2];
      analogDelay.process(temp, 128, state);
    }

    int[][] bufAna = new int[128][2];
    bufAna[0][0] = 50000000;
    bufAna[0][1] = 50000000;
    analogDelay.process(bufAna, 128, state);

    // Let's render 3 blocks of feedback
    int[][] outDig = new int[384][2];
    int[][] outAna = new int[384][2];

    for (int b = 0; b < 3; b++) {
      int[][] tempDig = new int[128][2];
      int[][] tempAna = new int[128][2];
      digitalDelay.process(tempDig, 128, state);
      analogDelay.process(tempAna, 128, state);
      for (int i = 0; i < 128; i++) {
        outDig[b * 128 + i][0] = tempDig[i][0];
        outDig[b * 128 + i][1] = tempDig[i][1];
        outAna[b * 128 + i][0] = tempAna[i][0];
        outAna[b * 128 + i][1] = tempAna[i][1];
      }
    }

    // Find the first echo peak for both digital and analog
    int maxDig = 0;
    int maxAna = 0;
    for (int i = 100; i < 160; i++) {
      maxDig = Math.max(maxDig, Math.abs(outDig[i][0]));
      maxAna = Math.max(maxAna, Math.abs(outAna[i][0]));
    }

    // Verify analog saturated peak is non-zero and strictly less than the digital peak
    assertTrue(maxAna > 1000, "Analog peak should be non-zero");
    assertTrue(
        maxAna < maxDig,
        "Analog saturation should clip the signal below digital scaling. Dig="
            + maxDig
            + ", Ana="
            + maxAna);
  }

  @Test
  public void testRateSweepResamplingBufferSwap() {
    Delay delay = new Delay();
    delay.pingPong = false;
    delay.analog = false;

    Delay.State state = new Delay.State();
    state.doDelay = true;
    state.userDelayRate = 1073741824; // 256 frames delay
    state.delayFeedbackAmount = 500000000;

    delay.setupWorkingState(state, 1, true);

    // Prime the delay
    for (int b = 0; b < 4; b++) {
      int[][] temp = new int[128][2];
      delay.process(temp, 128, state);
    }

    int[][] buffer = new int[128][2];
    buffer[0][0] = 1000000;
    delay.process(buffer, 128, state);

    // Render some blocks while sweeping the delay rate!
    for (int b = 0; b < 50; b++) {
      // Gradually sweep userDelayRate
      state.userDelayRate = 1073741824 - (b * 10000000);
      delay.setupWorkingState(state, 1, true);

      int[][] temp = new int[128][2];
      delay.process(temp, 128, state);
    }

    assertTrue(delay.isActive(), "Delay should remain active after rate sweep");
  }
}
