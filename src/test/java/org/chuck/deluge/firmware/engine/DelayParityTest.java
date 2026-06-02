package org.chuck.deluge.firmware.engine;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.chuck.deluge.firmware.dsp.delay.Delay;
import org.chuck.deluge.firmware.dsp.delay.DelayBuffer;
import org.chuck.deluge.firmware.model.SyncLevel;
import org.junit.jupiter.api.Test;

/**
 * Verifies the master delay produces echoes (GAP-08). The root bug was in DelayBuffer.advance: it
 * used signed int arithmetic for the 8-bit position diff, so at every high-byte wrap the diff went
 * negative and the write-advance/swap-counter callback was skipped — the buffer never started
 * reading back, so only the dry signal passed. Fixed by masking to 8 bits (matching the firmware's
 * uint8_t). Runs free-running (sync disabled) so the rate maps directly to a delay time.
 */
public class DelayParityTest {

  private static final int WARMUP = 7000;

  private static int rateForSeconds(double seconds) {
    long rate =
        (long)
            ((long) DelayBuffer.kNeutralSize * DelayBuffer.kMaxSampleValue / (seconds * 44100.0));
    return (int) Math.min(rate, Integer.MAX_VALUE);
  }

  private static double[] renderImpulse(int feedbackQ31, double delaySeconds, int total) {
    Delay delay = new Delay();
    delay.syncLevel = SyncLevel.SYNC_LEVEL_NONE; // free-running: rate == delay time
    Delay.State state = new Delay.State();
    state.userDelayRate = rateForSeconds(delaySeconds);
    state.delayFeedbackAmount = feedbackQ31;

    double[] out = new double[total];
    int block = 64;
    for (int start = 0; start < total; start += block) {
      int n = Math.min(block, total - start);
      StereoSample[] buf = new StereoSample[n];
      for (int i = 0; i < n; i++) {
        buf[i] = new StereoSample();
        if (start + i == WARMUP) {
          buf[i].l = 1 << 28;
          buf[i].r = 1 << 28;
        }
      }
      delay.setupWorkingState(state, 1 << 20, true);
      delay.process(buf, state);
      for (int i = 0; i < n; i++) out[start + i] = buf[i].l;
    }
    return out;
  }

  private static double energy(double[] x, int from, int to) {
    double s = 0;
    for (int i = from; i < Math.min(to, x.length); i++) s += Math.abs(x[i]);
    return s;
  }

  @Test
  public void delayProducesEchoes() {
    int total = 16000;
    double[] wet = renderImpulse(1073741824 /* 0.5 */, 0.05, total);
    double[] dry = renderImpulse(0, 0.05, total);

    int after = WARMUP + 500;
    double wetTail = energy(wet, after, total);
    double dryTail = energy(dry, after, total);

    assertTrue(wetTail > 0, "Delay with feedback must produce echoes (was frozen / dry-only)");
    assertTrue(
        wetTail > dryTail * 50,
        "Echo tail should dominate vs feedback=0 (wet=" + wetTail + ", dry=" + dryTail + ")");
  }
}
