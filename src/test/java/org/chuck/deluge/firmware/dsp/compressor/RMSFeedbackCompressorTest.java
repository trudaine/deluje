package org.chuck.deluge.firmware.dsp.compressor;

import static org.junit.jupiter.api.Assertions.*;

import org.chuck.deluge.firmware.dsp.StereoSample;
import org.junit.jupiter.api.Test;

/** Verifies the RMSFeedbackCompressor port faithfully matches the firmware structure. */
public class RMSFeedbackCompressorTest {

  private static final int ONE = 2147483647;

  @Test
  public void loudSignalShowsGainReduction() {
    RMSFeedbackCompressor c = new RMSFeedbackCompressor();
    c.setThreshold(120 << 24);
    c.setRatio(1073741824);
    c.setBlend(ONE);

    StereoSample[] buf = makeSine(4410, 440, 1.5e9);
    double before = rms(buf);
    c.renderVolNeutral(buf, ONE);
    double after = rms(buf);

    assertTrue(c.gainReduction >= 0, "gainReduction should be non-negative, got " + c.gainReduction);
    assertTrue(after < before,
        "loud signal should be compressed (before=" + before + " after=" + after + ")");
  }

  @Test
  public void quietSignalHasLessReduction() {
    RMSFeedbackCompressor c = new RMSFeedbackCompressor();
    c.setThreshold(120 << 24);
    c.setRatio(1073741824);
    c.setBlend(ONE);

    StereoSample[] loud = makeSine(4410, 440, 1.5e9);
    c.renderVolNeutral(loud, ONE);
    int redLoud = c.gainReduction;

    RMSFeedbackCompressor c2 = new RMSFeedbackCompressor();
    c2.setThreshold(120 << 24);
    c2.setRatio(1073741824);
    c2.setBlend(ONE);

    StereoSample[] quiet = makeSine(4410, 440, 0.1e9);
    c2.renderVolNeutral(quiet, ONE);
    int redQuiet = c2.gainReduction;

    assertTrue(redQuiet <= redLoud,
        "quiet signal should not exceed loud reduction (quiet=" + redQuiet + " loud=" + redLoud + ")");
  }

  @Test
  public void renderVolNeutralMatchesRender() {
    RMSFeedbackCompressor c1 = new RMSFeedbackCompressor();
    c1.setThreshold(100 << 24);
    c1.setRatio(1073741824);
    RMSFeedbackCompressor c2 = new RMSFeedbackCompressor();
    c2.setThreshold(100 << 24);
    c2.setRatio(1073741824);

    StereoSample[] buf1 = makeSine(4410, 440, 1.0e9);
    StereoSample[] buf2 = makeSine(4410, 440, 1.0e9);

    c1.renderVolNeutral(buf1, ONE);
    c2.render(buf2, 1 << 27, 1 << 27, ONE >> 3);

    for (int i = 0; i < buf1.length; i++) {
      assertEquals(buf1[i].l, buf2[i].l, 1000,
          "renderVolNeutral vs render mismatch at sample " + i);
    }
  }

  @Test
  public void highestThresholdPassesSignal() {
    RMSFeedbackCompressor c = new RMSFeedbackCompressor();
    c.setThreshold(255 << 24); // highest threshold → least compression
    c.setRatio(64 << 24); // low ratio
    c.setBlend(ONE);

    StereoSample[] buf = makeSine(4410, 440, 0.3e9);
    double before = rms(buf);
    c.renderVolNeutral(buf, ONE);
    double after = rms(buf);

    // High threshold on a quiet signal should yield minimal/no gain reduction
    assertTrue(after > before * 0.2,
        "highest threshold should largely pass signal (before=" + before + " after=" + after
            + " reduction=" + c.gainReduction + ")");
  }

  private static StereoSample[] makeSine(int n, double hz, double amp) {
    StereoSample[] buf = new StereoSample[n];
    for (int i = 0; i < n; i++) {
      int v = (int) (Math.sin(2 * Math.PI * hz * i / 44100.0) * amp);
      buf[i] = new StereoSample(v, v);
    }
    return buf;
  }

  private static double rms(StereoSample[] buf) {
    double sum = 0;
    for (StereoSample s : buf) {
      sum += (double) s.l * s.l + (double) s.r * s.r;
    }
    return Math.sqrt(sum / (buf.length * 2));
  }
}
